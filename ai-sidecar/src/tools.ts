import { Type, type TSchema } from "typebox";
import type { AgentTool } from "@earendil-works/pi-agent-core";

/**
 * Tool-callback client and JSON-schema -> TypeBox conversion helpers for the
 * ai-sidecar. Tool execution is delegated to the Java backend over HTTP.
 */

export const TOOL_CALLBACK_TIMEOUT_MS = 120_000;

export interface ToolCallbackRequest {
  runId: string;
  toolName: string;
  args: unknown;
  toolCallbackUrl: string;
  /** `X-Sidecar-Token` value; omitted when SIDECAR_TOKEN is unset. */
  token?: string;
  signal?: AbortSignal;
}

export interface ToolCallbackResult {
  content: string;
  isError: boolean;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * POST a tool invocation to the Java backend.
 *
 * Contract: 200 + {"content","isError":false} resolves successfully;
 * 200 + {"content","isError":true}, any non-2xx, or a malformed body
 * rejects with an Error so the agent records an errored tool result
 * (matching pi-agent-core's "throw on failure" tool convention).
 */
export async function callRemoteTool(req: ToolCallbackRequest): Promise<ToolCallbackResult> {
  const timeoutSignal = AbortSignal.timeout(TOOL_CALLBACK_TIMEOUT_MS);
  const signal = req.signal ? AbortSignal.any([req.signal, timeoutSignal]) : timeoutSignal;

  let response: Response;
  try {
    response = await fetch(req.toolCallbackUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(req.token ? { "X-Sidecar-Token": req.token } : {}),
      },
      body: JSON.stringify({ runId: req.runId, toolName: req.toolName, args: req.args }),
      signal,
    });
  } catch (error) {
    if (timeoutSignal.aborted) {
      throw new Error(`tool callback timed out after ${TOOL_CALLBACK_TIMEOUT_MS / 1000}s`);
    }
    throw new Error(`tool callback failed: ${errorMessage(error)}`);
  }

  const raw = await response.text();
  if (!response.ok) {
    throw new Error(`tool callback failed: HTTP ${response.status}`);
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    throw new Error(`tool callback failed: invalid JSON response (HTTP ${response.status})`);
  }
  if (
    typeof parsed !== "object" ||
    parsed === null ||
    typeof (parsed as { content?: unknown }).content !== "string"
  ) {
    throw new Error(`tool callback failed: response body must be {"content": string, "isError": boolean}`);
  }
  if ((parsed as { isError?: unknown }).isError === true) {
    throw new Error((parsed as { content: string }).content);
  }
  return {
    content: (parsed as { content: string }).content,
    isError: false,
  };
}

const MAX_SCHEMA_DEPTH = 12;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stringOptions(schema: Record<string, unknown>): Record<string, unknown> {
  const opts: Record<string, unknown> = {};
  for (const key of ["description", "minLength", "maxLength", "pattern", "format", "default"] as const) {
    if (schema[key] !== undefined) opts[key] = schema[key];
  }
  return opts;
}

function numberOptions(schema: Record<string, unknown>): Record<string, unknown> {
  const opts: Record<string, unknown> = {};
  for (const key of [
    "description",
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "multipleOf",
    "default",
  ] as const) {
    if (schema[key] !== undefined) opts[key] = schema[key];
  }
  return opts;
}

function literalUnion(values: unknown[], schema: Record<string, unknown>): TSchema {
  const literals = values.map((v) => Type.Literal(v as string | number | boolean));
  const description = schema.description !== undefined ? { description: schema.description as string } : undefined;
  return Type.Union(literals, description ?? {});
}

/**
 * Convert a JSON-schema fragment (as supplied by the Java backend in tool
 * `parameters`) into a TypeBox schema suitable for pi-agent-core tools.
 *
 * Standard JSON-schema semantics are preserved for the features the backend
 * actually emits (object/array/string/number/integer/boolean/null, enum,
 * const, required, additionalProperties, common constraints).
 * `additionalProperties` defaults to `true` (the JSON-schema default), unlike
 * TypeBox's strict default. Anything unsupported degrades to
 * `Type.Unknown()` (JSON schema `{}`).
 */
export function jsonSchemaToTypeBox(schema: unknown, depth = 0): TSchema {
  if (depth > MAX_SCHEMA_DEPTH || !isRecord(schema)) {
    return Type.Unknown();
  }

  if (schema.const !== undefined) {
    return Type.Literal(schema.const as string | number | boolean);
  }
  if (Array.isArray(schema.enum)) {
    return literalUnion(schema.enum, schema);
  }

  const description = schema.description !== undefined ? { description: schema.description as string } : undefined;
  const type = schema.type;

  if (
    type === "object" ||
    (type === undefined && ("properties" in schema || "required" in schema || "additionalProperties" in schema))
  ) {
    const properties: Record<string, TSchema> = {};
    if (isRecord(schema.properties)) {
      for (const [key, sub] of Object.entries(schema.properties)) {
        properties[key] = jsonSchemaToTypeBox(sub, depth + 1);
      }
    }
    const required = new Set<string>(
      Array.isArray(schema.required) ? schema.required.filter((k): k is string => typeof k === "string") : [],
    );
    for (const key of Object.keys(properties)) {
      if (!required.has(key)) {
        properties[key] = Type.Optional(properties[key]);
      }
    }
    const extra = schema.additionalProperties;
    let additionalProperties: boolean | TSchema = true; // JSON-schema default
    if (extra === false) {
      additionalProperties = false;
    } else if (isRecord(extra)) {
      additionalProperties = jsonSchemaToTypeBox(extra, depth + 1);
    }
    return Type.Object(properties, {
      additionalProperties,
      ...(description ?? {}),
      ...(schema.minProperties !== undefined ? { minProperties: schema.minProperties as number } : {}),
      ...(schema.maxProperties !== undefined ? { maxProperties: schema.maxProperties as number } : {}),
    });
  }

  if (type === "array") {
    return Type.Array(jsonSchemaToTypeBox(schema.items ?? {}, depth + 1), {
      ...(description ?? {}),
      ...(schema.minItems !== undefined ? { minItems: schema.minItems as number } : {}),
      ...(schema.maxItems !== undefined ? { maxItems: schema.maxItems as number } : {}),
    });
  }

  switch (type) {
    case "string":
      return Type.String(stringOptions(schema));
    case "number":
      return Type.Number(numberOptions(schema));
    case "integer":
      return Type.Integer(numberOptions(schema));
    case "boolean":
      return Type.Boolean(description ?? {});
    case "null":
      return Type.Null();
    default:
      break;
  }

  if (Array.isArray(schema.anyOf) && schema.anyOf.length > 0) {
    return Type.Union(schema.anyOf.map((s) => jsonSchemaToTypeBox(s, depth + 1)));
  }
  if (Array.isArray(schema.oneOf) && schema.oneOf.length > 0) {
    return Type.Union(schema.oneOf.map((s) => jsonSchemaToTypeBox(s, depth + 1)));
  }
  return Type.Unknown();
}

/**
 * A tool declaration exactly as the Java backend sends it: name,
 * description, and a JSON-schema `parameters` object.
 */
export interface RequestedTool {
  name: string;
  description: string;
  parameters: unknown;
}

export interface BuildToolsInput {
  tools: RequestedTool[];
  runId: string;
  toolCallbackUrl: string;
  token?: string;
}

/**
 * Build pi agent tools whose execution is delegated to the Java backend.
 * Non-2xx responses, malformed bodies and `isError: true` results throw so
 * the agent records an errored tool result (matching pi-agent-core's
 * "throw on failure" convention).
 */
export function buildAgentTools(input: BuildToolsInput): AgentTool<any>[] {
  return input.tools.map((tool) => ({
    name: tool.name,
    label: tool.name,
    description: tool.description,
    parameters: jsonSchemaToTypeBox(tool.parameters ?? {}),
    execute: async (
      _toolCallId: string,
      args: unknown,
      signal?: AbortSignal,
    ): Promise<{ content: { type: "text"; text: string }[]; details: Record<string, never> }> => {
      const result = await callRemoteTool({
        runId: input.runId,
        toolName: tool.name,
        args,
        toolCallbackUrl: input.toolCallbackUrl,
        token: input.token,
        signal,
      });
      if (result.isError) {
        throw new Error(result.content);
      }
      return { content: [{ type: "text", text: result.content }], details: {} };
    },
  }));
}
