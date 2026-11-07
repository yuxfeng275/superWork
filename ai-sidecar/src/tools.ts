import {
  type Optional,
  type Schema,
  arr,
  bool,
  int,
  lit,
  nul,
  num,
  obj,
  opt,
  str,
  union,
  unknown as unknownSchema,
} from "@earendil-works/pi-schema";
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

type StrOptions = NonNullable<Parameters<typeof str>[0]>;
type NumOptions = NonNullable<Parameters<typeof num>[0]>;

function stringOptions(schema: Record<string, unknown>): StrOptions {
  const opts: Record<string, unknown> = {};
  for (const key of ["description", "minLength", "maxLength", "pattern", "format", "default"] as const) {
    if (schema[key] !== undefined) opts[key] = schema[key];
  }
  return opts as StrOptions;
}

function numberOptions(schema: Record<string, unknown>): NumOptions {
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

function literalUnion(values: unknown[], schema: Record<string, unknown>): Schema {
  const literals = values.map((v) => lit(v as string | number | boolean | null));
  const description = schema.description !== undefined ? { description: schema.description as string } : undefined;
  return union(literals, description ?? {});
}

/**
 * Convert a JSON-schema fragment (as supplied by the Java backend in tool
 * `parameters`) into a TypeBox schema suitable for pi-agent-core tools.
 *
 * Standard JSON-schema semantics are preserved for the features the backend
 * actually emits (object/array/string/number/integer/boolean/null, enum,
 * const, required, additionalProperties, common constraints).
 * `additionalProperties` defaults to `true` (the JSON-schema default), unlike
 * pi-schema's `obj()` default (closed). Anything unsupported degrades to
 * `unknown()` (JSON schema `{}`).
 */
export function jsonSchemaToTypeBox(schema: unknown, depth = 0): Schema {
  if (depth > MAX_SCHEMA_DEPTH || !isRecord(schema)) {
    return unknownSchema();
  }

  if (schema.const !== undefined) {
    return lit(schema.const as string | number | boolean);
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
    const properties: Record<string, Schema | Optional> = {};
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
        properties[key] = opt(properties[key] as Schema);
      }
    }
    const extra = schema.additionalProperties;
    const objOptions: Record<string, unknown> = {
      // JSON-schema defaults to "anything goes"; pi-schema's obj() is closed.
      open: true,
      ...(description ?? {}),
      ...(schema.minProperties !== undefined ? { minProperties: schema.minProperties as number } : {}),
      ...(schema.maxProperties !== undefined ? { maxProperties: schema.maxProperties as number } : {}),
    };
    if (extra === false) {
      objOptions.open = false;
    } else if (isRecord(extra)) {
      objOptions.additionalProperties = jsonSchemaToTypeBox(extra, depth + 1);
    }
    return obj(properties, objOptions as never);
  }

  if (type === "array") {
    return arr(jsonSchemaToTypeBox(schema.items ?? {}, depth + 1), {
      ...(description ?? {}),
      ...(schema.minItems !== undefined ? { minItems: schema.minItems as number } : {}),
      ...(schema.maxItems !== undefined ? { maxItems: schema.maxItems as number } : {}),
    });
  }

  switch (type) {
    case "string":
      return str(stringOptions(schema));
    case "number":
      return num(numberOptions(schema));
    case "integer":
      return int(numberOptions(schema));
    case "boolean":
      return bool(description ?? {});
    case "null":
      return nul();
    default:
      break;
  }

  if (Array.isArray(schema.anyOf) && schema.anyOf.length > 0) {
    return union(schema.anyOf.map((s) => jsonSchemaToTypeBox(s, depth + 1)));
  }
  if (Array.isArray(schema.oneOf) && schema.oneOf.length > 0) {
    return union(schema.oneOf.map((s) => jsonSchemaToTypeBox(s, depth + 1)));
  }
  return unknownSchema();
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
