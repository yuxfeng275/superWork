import type { IncomingMessage, ServerResponse } from "node:http";
import { Agent, type AgentEvent, type AgentMessage, type AgentTool, type ThinkingLevel } from "@earendil-works/pi-agent-core";
import { createModels, createProvider } from "@earendil-works/pi-ai";
import type { Message, Model } from "@earendil-works/pi-ai";
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy";
import { buildAgentTools, type RequestedTool } from "./tools.js";

/**
 * POST /v1/runs implementation: validates the Java request, then drives a
 * pi-agent-core Agent and relays its lifecycle to the client as SSE per
 * docs/ai-agent-sidecar.md.
 */

export const MAX_MESSAGES = 200;
export const MAX_RUN_MS = 600_000;
export const HEARTBEAT_MS = 15_000;
/** GLM (Zhipu) OpenAI-compatible chat/completions base; the only LLM endpoint this sidecar talks to. */
export const DEFAULT_GLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
export const DEFAULT_THINKING_LEVEL: ThinkingLevel = "max";
export const THINKING_LEVELS: readonly ThinkingLevel[] = [
  "off", "minimal", "low", "medium", "high", "xhigh", "max",
] as const;
const MAX_BODY_BYTES = 10 * 1024 * 1024;
const ERROR_MESSAGE_MAX = 2000;

export interface RunRequest {
  runId: string;
  provider: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  systemPrompt: string;
  /** Requested GLM thinking level; defaults to "max". */
  thinkingLevel?: ThinkingLevel;
  /** pi-agent-core AgentMessage JSON, passed through opaquely. */
  messages: OpaqueAgentMessage[];
  tools: RequestedTool[];
  toolCallbackUrl?: string;
}

/** Java-persisted AgentMessage JSON; treated as an opaque structure. */
export type OpaqueAgentMessage = Record<string, unknown>;

export type ValidationResult = { ok: true; value: RunRequest } | { ok: false; message: string };

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Validate the request body against the sidecar contract:
 * - runId/provider/baseUrl/apiKey/model/systemPrompt required strings
 * - thinkingLevel optional, one of off|minimal|low|medium|high|xhigh|max (default "max")
 * - messages: array, <= 200; last message must be the current user (or
 * - tools: array of { name, description?, parameters? } objects
 * - toolCallbackUrl required when tools are declared
 */
export function validateRunRequest(body: unknown): ValidationResult {
  if (!isPlainObject(body)) {
    return { ok: false, message: "request body must be a JSON object" };
  }

  const requiredFields = ["runId", "provider", "baseUrl", "apiKey", "model", "systemPrompt"] as const;
  for (const key of requiredFields) {
    if (typeof body[key] !== "string") {
      return { ok: false, message: `missing or invalid field: ${key}` };
    }
  }

  let thinkingLevel: ThinkingLevel | undefined;
  if (body.thinkingLevel !== undefined) {
    if (typeof body.thinkingLevel !== "string" || !THINKING_LEVELS.includes(body.thinkingLevel as ThinkingLevel)) {
      return {
        ok: false,
        message: `thinkingLevel must be one of: ${THINKING_LEVELS.join("|")}`,
      };
    }
    thinkingLevel = body.thinkingLevel as ThinkingLevel;
  }


  if (body.toolCallbackUrl !== undefined && typeof body.toolCallbackUrl !== "string") {
    return { ok: false, message: "toolCallbackUrl must be a string" };
  }

  const rawMessages = body.messages;
  if (!Array.isArray(rawMessages)) {
    return { ok: false, message: "messages must be an array" };
  }
  if (rawMessages.length > MAX_MESSAGES) {
    return { ok: false, message: `messages exceeds the ${MAX_MESSAGES} entry limit` };
  }
  if (rawMessages.length === 0) {
    return { ok: false, message: "messages must not be empty" };
  }
  const messages: OpaqueAgentMessage[] = [];
  for (const entry of rawMessages) {
    if (!isPlainObject(entry)) {
      return { ok: false, message: "each message must be a JSON object" };
    }
    messages.push(entry);
  }
  const lastRole = messages[messages.length - 1].role;
  if (lastRole !== "user" && lastRole !== "toolResult") {
    return { ok: false, message: 'last message must have role "user" (or "toolResult")' };
  }

  const rawTools = body.tools;
  if (!Array.isArray(rawTools)) {
    return { ok: false, message: "tools must be an array" };
  }
  const tools: RequestedTool[] = [];
  for (const entry of rawTools) {
    if (!isPlainObject(entry) || typeof entry.name !== "string" || entry.name.length === 0) {
      return { ok: false, message: "each tool must be an object with a non-empty string name" };
    }
    const description = entry.description === undefined ? "" : entry.description;
    if (typeof description !== "string") {
      return { ok: false, message: `tool "${entry.name}" description must be a string` };
    }
    const parameters = entry.parameters ?? {};
    if (!isPlainObject(parameters)) {
      return { ok: false, message: `tool "${entry.name}" parameters must be an object` };
    }
    tools.push({ name: entry.name, description, parameters });
  }

  const toolCallbackUrl = body.toolCallbackUrl as string | undefined;
  if (tools.length > 0 && typeof toolCallbackUrl !== "string") {
    return { ok: false, message: "toolCallbackUrl is required when tools are declared" };
  }

  return {
    ok: true,
    value: {
      runId: body.runId as string,
      provider: body.provider as string,
      baseUrl: body.baseUrl as string,
      apiKey: body.apiKey as string,
      model: body.model as string,
      systemPrompt: body.systemPrompt as string,
      thinkingLevel,
      messages,
      tools,
      toolCallbackUrl,
    },
  };
}

/* ------------------------------------------------------------------ */
/* Agent lifecycle abstraction (real pi Agent is injected by default)  */
/* ------------------------------------------------------------------ */

export interface AgentLike {
  subscribe(listener: (event: AgentEvent, signal: AbortSignal) => void | Promise<void>): () => void;
  continue(): Promise<void>;
  abort(): void;
  readonly state: { errorMessage?: string };
}

export interface AgentFactoryInput {
  runId: string;
  provider: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  systemPrompt: string;
  /** Requested GLM thinking level; defaults to "max". */
  thinkingLevel?: ThinkingLevel;
  messages: OpaqueAgentMessage[];
  tools: AgentTool<any>[];
  /** Header value for X-Sidecar-Token, when SIDECAR_TOKEN is set. */
  token?: string;
  toolCallbackUrl?: string;
}

export type AgentFactory = (input: AgentFactoryInput) => AgentLike;

function trimTrailingSlash(url: string): string {
  return url.replace(/\/+$/, "");
}

/**
 * GLM model served through pi-ai's generic openai-completions API impl.
 *
 * `reasoning: true` is what enables thinking: pi-ai's URL-based compat
 * auto-detection matches `open.bigmodel.cn` and applies `thinkingFormat:
 * "zai"`, emitting `thinking: { type: "enabled", clear_thinking: false }`
 * on stream calls whenever a reasoning level is requested (and
 * `thinking: { type: "disabled" }` for level "off"). `reasoning_effort` is
 * intentionally NOT sent — Zhipu's OpenAI-compatible endpoint rejects or
 * ignores it, and pi-ai suppresses it for zai-format providers. The
 * thinkingLevel string itself does not cross the wire on this endpoint;
 * the on/off switch does.
 *
 * `thinkingLevelMap` without xhigh/max entries would make pi-ai clamp
 * "max" down to "high"; since the zai format carries no level anyway, we
 * mark all levels supported to preserve the requested level internally
 * (state.thinkingLevel) without inventing request fields Zhipu does not
 * define.
 */
function makeOpenAICompletionsModel(provider: string, baseUrl: string, id: string): Model<"openai-completions"> {
  return {
    id,
    name: id,
    api: "openai-completions",
    provider,
    baseUrl: trimTrailingSlash(baseUrl),
    reasoning: true,
    thinkingLevelMap: {
      off: null,
      minimal: "minimal",
      low: "low",
      medium: "medium",
      high: "high",
      xhigh: "xhigh",
      max: "max",
    },
    input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 200_000,
    maxTokens: 8192,
    compat: {
      supportsDeveloperRole: false,
      supportsReasoningEffort: false,
      maxTokensField: "max_tokens",
    },
  };
}

/**
 * Default factory: registers a per-run OpenAI-compatible provider pointed at
 * the GLM base URL (default https://open.bigmodel.cn/api/paas/v4, env
 * GLM_BASE_URL) and runs a pi Agent over it. GLM_API_KEY seeds the provider
 * auth when the request body omits apiKey.
 */
export function buildPiAgent(input: AgentFactoryInput): AgentLike {
  const models = createModels();
  const provider = createProvider({
    id: input.provider,
    name: input.provider,
    baseUrl: trimTrailingSlash(input.baseUrl),
    auth: {
      apiKey: {
        name: input.provider,
        resolve: async () => ({
          auth: { apiKey: input.apiKey || process.env.GLM_API_KEY || undefined },
        }),
      },
    },
    models: [makeOpenAICompletionsModel(input.provider, input.baseUrl, input.model)],
    api: openAICompletionsApi(),
  });
  models.setProvider(provider);

  const model = models.getModel(input.provider, input.model);
  if (!model) {
    throw new Error(`model "${input.model}" could not be registered for provider "${input.provider}"`);
  }

  // Java-persisted AgentMessages are replayed verbatim; timestamps are
  // normalized when absent so transcript invariants hold.
  const messages = input.messages.map((m) =>
    typeof m.timestamp === "number" ? (m as unknown as AgentMessage) : ({ ...m, timestamp: Date.now() } as unknown as AgentMessage),
  );

  const agent = new Agent({
    initialState: {
      systemPrompt: input.systemPrompt,
      model: model as Model<any>,
      thinkingLevel: input.thinkingLevel ?? DEFAULT_THINKING_LEVEL,
      messages,
    },
    streamFn: models.streamSimple.bind(models),
    convertToLlm: (msgs: AgentMessage[]): Message[] =>
      msgs.filter((m) => m.role === "user" || m.role === "assistant" || m.role === "toolResult") as unknown as Message[],
  });
  return agent;
}

/* ------------------------------------------------------------------ */
/* HTTP handler                                                        */
/* ------------------------------------------------------------------ */

export interface RunHandlerOptions {
  /** SIDECAR_TOKEN value; undefined disables the token check. */
  token?: string;
  createAgent?: AgentFactory;
  maxRunMs?: number;
  heartbeatMs?: number;
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  if (res.writableEnded) return;
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function errorMessageText(error: unknown): string {
  const text = error instanceof Error ? error.message : String(error);
  return text.length > ERROR_MESSAGE_MAX ? `${text.slice(0, ERROR_MESSAGE_MAX)}…` : text;
}

async function readJsonBody(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of req) {
    const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    total += buf.length;
    if (total > MAX_BODY_BYTES) {
      throw new Error("request body too large");
    }
    chunks.push(buf);
  }
  const raw = Buffer.concat(chunks).toString("utf8");
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    throw new Error("invalid JSON body");
  }
}

/** Normalize a tool result into a string suitable for the SSE `result` field. */
function resultToText(result: unknown): string {
  if (typeof result === "string") return result;
  if (typeof result === "number" || typeof result === "boolean") return String(result);
  if (result instanceof Error) return result.message;
  if (Array.isArray(result)) return result.map(resultToText).join("");
  if (result && typeof result === "object") {
    const record = result as Record<string, unknown>;
    if (record.type === "text") return typeof record.text === "string" ? record.text : "";
    if (record.content !== undefined) return resultToText(record.content);
    if (typeof record.errorMessage === "string") return record.errorMessage;
    if (typeof record.message === "string" && Object.keys(record).length === 1) return record.message;
  }
  try {
    return JSON.stringify(result);
  } catch {
    return String(result);
  }
}

/**
 * Handle POST /v1/runs: token check, body validation, then an SSE stream.
 * Resolves once the response is fully written.
 */
export async function handleRun(
  req: IncomingMessage,
  res: ServerResponse,
  options: RunHandlerOptions = {},
): Promise<void> {
  const token = options.token !== undefined ? options.token : process.env.SIDECAR_TOKEN;
  const createAgent = options.createAgent ?? buildPiAgent;
  const maxRunMs = options.maxRunMs ?? MAX_RUN_MS;
  const heartbeatMs = options.heartbeatMs ?? HEARTBEAT_MS;

  // 1) Auth: X-Sidecar-Token must equal SIDECAR_TOKEN when it is set.
  const supplied = req.headers["x-sidecar-token"];
  if (token !== undefined && supplied !== token) {
    sendJson(res, 401, { error: { code: "unauthorized" } });
    return;
  }

  // 2) Body read + validation
  let body: unknown;
  try {
    body = await readJsonBody(req);
  } catch (error) {
    sendJson(res, 400, { error: { code: "invalid_request", message: errorMessageText(error) } });
    return;
  }
  const validated = validateRunRequest(body);
  if (!validated.ok) {
    sendJson(res, 400, { error: { code: "invalid_request", message: validated.message } });
    return;
  }
  const params = validated.value;

  // GLM-only: the sidecar talks exclusively to the Zhipu OpenAI-compatible
  // endpoint. baseUrl defaults to GLM_BASE_URL; apiKey to GLM_API_KEY; the
  // key itself is only required once a run actually starts.
  if (params.provider !== "zhipu") {
    sendJson(res, 400, {
      error: { code: "invalid_request", message: 'provider must be "zhipu" (GLM is the only supported LLM)' },
    });
    return;
  }
  params.baseUrl = params.baseUrl || process.env.GLM_BASE_URL || DEFAULT_GLM_BASE_URL;
  params.apiKey = params.apiKey || process.env.GLM_API_KEY || "";

  // 3) Build tools (remote HTTP callback execution).
  let tools: AgentTool<any>[];
  try {
    tools = buildAgentTools({
      tools: params.tools,
      runId: params.runId,
      toolCallbackUrl: params.toolCallbackUrl ?? "",
      token,
    });
  } catch (error) {
    sendJson(res, 400, { error: { code: "invalid_request", message: errorMessageText(error) } });
    return;
  }

  // 4) SSE preamble
  if (res.headersSent) {
    sendJson(res, 500, { error: { code: "internal_error", message: "headers already sent" } });
    return;
  }
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
  });
  res.flushHeaders();

  let finished = false;
  let clientGone = false;
  const emit = (event: string, data: unknown): void => {
    if (finished || clientGone || res.writableEnded || res.destroyed) return;
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  };

  await streamRunEvents(
    {
      params,
      token,
      tools,
      createAgent,
      maxRunMs,
      heartbeatMs,
    },
    res,
    {
      emit,
      onFinished: () => {
        finished = true;
      },
      onClientGone: () => {
        clientGone = true;
      },
    },
  );
}

interface StreamRunContext {
  params: RunRequest;
  token?: string;
  tools: AgentTool<any>[];
  createAgent: AgentFactory;
  maxRunMs: number;
  heartbeatMs: number;
}

interface StreamRunCallbacks {
  emit: (event: string, data: unknown) => void;
  onFinished: () => void;
  onClientGone: () => void;
}

/**
 * Drive the agent and translate its events into SSE frames.
 *
 * newMessages (for run_end) accumulates every message the run appends to the
 * transcript (assistant and toolResult), in transcript order. An assistant
 * message's `index` (message_start/message_delta) is its position in that
 * array, which is known when its streaming begins because everything before
 * it has already been appended.
 */
async function streamRunEvents(ctx: StreamRunContext, res: ServerResponse, cb: StreamRunCallbacks): Promise<void> {
  const { params, emit, onFinished, onClientGone } = { ...ctx, ...cb };
  const baseMessageCount = params.messages.length;
  const newMessages: AgentMessage[] = [];

  let agent: AgentLike | undefined;
  let agentCreationError: string | undefined;
  let sawAgentEnd = false;
  let agentEndMessages: AgentMessage[] | undefined;
  let timedOut = false;
  let caught: unknown;
  let clientGone = false;
  let terminalSent = false;

  const endResponse = (): void => {
    onFinished();
    if (!res.writableEnded && !res.destroyed) {
      res.end();
    }
  };

  const sendError = (code: string, message: string): void => {
    if (terminalSent) return;
    emit("error", { code, message });
    terminalSent = true;
  };

  // Key validation happens only when a run starts, not at boot.
  if (!params.apiKey) {
    sendError("invalid_request", "GLM_API_KEY (or request apiKey) is required to start a run");
    endResponse();
    return;
  }

  emit("run_start", { runId: params.runId });

  try {
    agent = ctx.createAgent({
      runId: params.runId,
      provider: params.provider,
      baseUrl: params.baseUrl,
      apiKey: params.apiKey,
      model: params.model,
      systemPrompt: params.systemPrompt,
      messages: params.messages,
      thinkingLevel: params.thinkingLevel,
      tools: ctx.tools,
      token: ctx.token,
      toolCallbackUrl: params.toolCallbackUrl,
    });
  } catch (error) {
    agentCreationError = errorMessageText(error);
  }

  if (!agent) {
    sendError("internal_error", agentCreationError ?? "agent creation failed");
    endResponse();
    return;
  }

  // Heartbeat: keep proxies alive while the run is idle between events.
  const heartbeat = setInterval(() => {
    if (!res.writableEnded && !res.destroyed) {
      res.write(": ping\n\n");
    }
  }, ctx.heartbeatMs);
  heartbeat.unref?.();

  // Overall hard cap (default 600s).
  const runTimer = setTimeout(() => {
    timedOut = true;
    agent?.abort();
  }, ctx.maxRunMs);
  runTimer.unref?.();

  const onClose = (): void => {
    clientGone = true;
    onClientGone();
    agent?.abort();
    clearInterval(heartbeat);
    clearTimeout(runTimer);
  };
  res.once("close", onClose);

  const unsubscribe = agent.subscribe((event: AgentEvent) => {
    switch (event.type) {
      case "message_start": {
        if (event.message.role === "assistant") {
          emit("message_start", { index: newMessages.length });
        }
        break;
      }
      case "message_update": {
        const ame = event.assistantMessageEvent;
        if (ame.type === "text_delta" || ame.type === "thinking_delta") {
          emit("message_delta", {
            index: newMessages.length,
            delta: { type: ame.type, text: ame.delta },
          });
        }
        break;
      }

      case "message_end": {
        const message = event.message;
        if (message.role === "assistant") {
          // Contract: message_end carries {message} only (no index).
          emit("message_end", { message });
        }
        newMessages.push(message);
        break;
      }
      case "tool_execution_start": {
        emit("tool_execution_start", {
          toolCallId: event.toolCallId,
          toolName: event.toolName,
          args: event.args,
        });
        break;
      }
      case "tool_execution_end": {
        emit("tool_execution_end", {
          toolCallId: event.toolCallId,
          result: resultToText(event.result),
          isError: event.isError,
        });
        break;
      }
      case "agent_end": {
        sawAgentEnd = true;
        agentEndMessages = event.messages;
        break;
      }
      default:
        break; // agent_start / turn_start / turn_end / tool_execution_update
    }
  });

  try {
    await agent.continue();
  } catch (error) {
    caught = error;
  } finally {
    clearInterval(heartbeat);
    clearTimeout(runTimer);
    unsubscribe();
    res.removeListener("close", onClose);
  }

  if (clientGone || res.writableEnded || res.destroyed) {
    endResponse();
    return;
  }

  if (timedOut) {
    sendError("timeout", `run exceeded the ${ctx.maxRunMs / 1000}s limit`);
  } else if (caught) {
    sendError("internal_error", errorMessageText(caught));
  } else if (sawAgentEnd && !agentCreationError) {
    const agentStateError = agent.state?.errorMessage;
    if (agentStateError) {
      sendError("provider_error", agentStateError);
    } else {
      const transcriptAdds = agentEndMessages ? agentEndMessages.slice(baseMessageCount) : newMessages;
      const finalMessages =
        agentEndMessages && transcriptAdds.length === newMessages.length ? transcriptAdds : newMessages;
      emit("run_end", { newMessages: finalMessages });
      terminalSent = true;
    }
  } else {
    sendError(agentCreationError ? "internal_error" : "provider_error", agentCreationError ?? "agent ended without agent_end");
  }

  endResponse();
}
