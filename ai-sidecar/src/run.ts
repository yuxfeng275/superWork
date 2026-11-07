import type { IncomingMessage, ServerResponse } from "node:http";
import {
  Agent,
  type AgentEvent,
  type AgentMessage,
  type AgentTool,
  type ThinkingLevel,
} from "@earendil-works/pi-agent-core";
import { createModels, createProvider, type Api, type MutableModels } from "@earendil-works/pi-ai";
import type { FetchFunction, Model } from "@earendil-works/pi-ai";
import { builtinModels } from "@earendil-works/pi-ai/providers/all";
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy";
import { ProxyAgent, fetch as undiciFetch } from "undici";
import { buildAgentTools, type RequestedTool } from "./tools.js";

/**
 * POST /v1/runs implementation: validates the Java request, then drives a
 * pi-agent-core Agent and relays its lifecycle to the client as SSE per
 * docs/ai-agent-sidecar.md.
 */

export const MAX_MESSAGES = 200;
export const MAX_RUN_MS = 600_000;
export const HEARTBEAT_MS = 15_000;
/** Zhipu GLM OpenAI-compatible chat/completions base. */
export const DEFAULT_GLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
/** DeepSeek OpenAI-compatible chat/completions base. */
export const DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com";
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
  /** Requested thinking level; defaults to "max". */
  thinkingLevel?: ThinkingLevel;
  /** pi-agent-core AgentMessage JSON, passed through opaquely. */
  messages: OpaqueAgentMessage[];
  tools: RequestedTool[];
  toolCallbackUrl?: string;
  /** HTTP CONNECT 前代（如 http://mihomo:7890），用于出口被墙的提供方（如 api.b.ai）；空 = 直连 */
  proxy?: string;
  /** 会话 id：同会话续跑时传给 pi Agent，供 provider 的提示词缓存命中。 */
  sessionId?: string;
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

  if (body.toolCallbackUrl !== undefined && typeof body.toolCallbackUrl !== "string") {
    return { ok: false, message: "toolCallbackUrl must be a string" };
  }
  if (body.proxy !== undefined && typeof body.proxy !== "string") {
    return { ok: false, message: "proxy must be a string" };
  }
  if (body.sessionId !== undefined && typeof body.sessionId !== "string") {
    return { ok: false, message: "sessionId must be a string" };
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
      proxy: body.proxy as string | undefined,
      sessionId: body.sessionId as string | undefined,
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
  /** HTTP CONNECT 前代；非空时提供方请求走代理 fetch */
  proxy?: string;
    /** 会话 id（可空）；有值时透传给 pi Agent 供 provider 缓存命中。 */
  sessionId?: string;
}

/** 构造走 HTTP CONNECT 前代的 fetch（undici ProxyAgent），proxy 为空返回 undefined = 直连。 */
function proxyFetchFor(proxy?: string): FetchFunction | undefined {
  if (!proxy?.trim()) return undefined;
  const dispatcher = new ProxyAgent(proxy.trim());
  return ((url: unknown, init?: Record<string, unknown>) =>
    undiciFetch(url as never, { ...init, dispatcher } as never)) as unknown as FetchFunction;
}

export type AgentFactory = (input: AgentFactoryInput) => AgentLike;

/** Provider id (as sent by Java) -> pi-ai catalog provider id. */
const CATALOG_PROVIDER_ALIASES: Record<string, string> = { zhipu: "zai" };

/**
 * Output-token ceiling for models the pi-ai catalog does not know. Kept
 * deliberately below any official 1M-window model's real maxTokens so a
 * fallback never asks for more than the provider will grant.
 */
export const FALLBACK_MAX_TOKENS = 16_384;

function trimTrailingSlash(url: string): string {
  return url.replace(/\/+$/, "");
}

/** Provider id -> env var holding its API key (request body key wins over these). */
function envApiKeyFor(provider: string): string | undefined {
  if (provider === "zhipu") return process.env.GLM_API_KEY;
  if (provider === "deepseek") return process.env.DEEPSEEK_API_KEY;
  return undefined;
}

/** Fill official defaults; custom OpenAI-compatible relays must send baseUrl. */
export function applyProviderDefaults(params: RunRequest): string | null {
  if (params.provider === "zhipu") {
    params.baseUrl = params.baseUrl || process.env.ZHIPU_BASE_URL || DEFAULT_GLM_BASE_URL;
    params.apiKey = params.apiKey || process.env.GLM_API_KEY || "";
    return null;
  }
  if (params.provider === "deepseek") {
    params.baseUrl = params.baseUrl || process.env.DEEPSEEK_BASE_URL || DEFAULT_DEEPSEEK_BASE_URL;
    params.apiKey = params.apiKey || process.env.DEEPSEEK_API_KEY || "";
    return null;
  }
  if (!params.baseUrl.trim()) {
    return "baseUrl is required for OpenAI-compatible providers";
  }
  return null;
}

/**
 * Model served through pi-ai's generic openai-completions API impl.
 *
 * Zhipu GLM (`open.bigmodel.cn`): pi-ai's URL-based compat auto-detection
 * applies `thinkingFormat: "zai"`, emitting
 * `thinking: { type: "enabled", clear_thinking: false }` on stream calls
 * whenever a reasoning level is requested (and
 * `thinking: { type: "disabled" }` for level "off"). `reasoning_effort` is
 * intentionally NOT sent — Zhipu's OpenAI-compatible endpoint rejects or
 * ignores it, and pi-ai suppresses it for zai-format providers. The
 * thinkingLevel string itself does not cross the wire on this endpoint;
 * the on/off switch does.
 *
 * DeepSeek (`api.deepseek.com`): pi-ai auto-detects `thinkingFormat:
 * "deepseek"` which sends `thinking: { type: "enabled"|"disabled" }` and
 * maps reasoning deltas onto assistant `thinking` content parts, matching
 * the zai stream shape our SSE relay expects.
 *
 * `thinkingLevelMap` without xhigh/max entries would make pi-ai clamp
 * "max" down to "high"; we mark all levels supported to preserve the
 * requested level internally without inventing request fields the
 * provider does not define.
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
    // Fallback-only values: a custom/baseUrl-overridden model that the pi-ai
    // catalog does not know about. Prices stay 0 here on purpose — a model we
    // have no official price for must not contribute invented cost numbers to
    // ai_agent_usage; the catalog branch in resolveModelDefinition() supplies
    // the real cost/capabilities whenever the model is a known one.
    input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 200_000,
    maxTokens: FALLBACK_MAX_TOKENS,
    compat: {
      supportsDeveloperRole: false,
      supportsReasoningEffort: false,
      maxTokensField: "max_tokens",
    },
  };
}

/**
 * pi-ai's official model catalog, built lazily (the generated catalog is a
 * large module, so it is only loaded on the first run). `null` marks a failed
 * build so we never retry the import on every request.
 */
let catalogModels: MutableModels | null | undefined;

function piCatalog(): MutableModels | null {
  if (catalogModels === undefined) {
    try {
      catalogModels = builtinModels();
    } catch {
      catalogModels = null;
    }
  }
  return catalogModels;
}

/**
 * Look up a model in pi-ai's official catalog.
 *
 * The backend's provider ids are our own (`zhipu`); pi-ai catalogues the same
 * endpoint under `zai`, hence the alias. Returns undefined for any model the
 * catalog does not ship (relays, private deployments, new ids).
 */
export function catalogModelFor(provider: string, modelId: string): Model<Api> | undefined {
  const catalog = piCatalog();
  if (!catalog) return undefined;
  const candidates = CATALOG_PROVIDER_ALIASES[provider] === undefined
    ? [provider]
    : [provider, CATALOG_PROVIDER_ALIASES[provider]];
  for (const providerId of candidates) {
    const found = catalog.getModel(providerId, modelId);
    if (found) return found;
  }
  return undefined;
}

/**
 * Model definition used for a run: the catalog entry's real `cost`,
 * `contextWindow`, `maxTokens`, `input` and `reasoning` when pi-ai knows the
 * model, otherwise the conservative hand-written fallback above. Prices are
 * never invented locally — a catalog miss reports 0 rather than a guess.
 */
export function resolveModelDefinition(
  provider: string,
  baseUrl: string,
  modelId: string,
): Model<"openai-completions"> {
  const fallback = makeOpenAICompletionsModel(provider, baseUrl, modelId);
  const catalogModel = catalogModelFor(provider, modelId);
  if (!catalogModel) return fallback;
  return {
    ...fallback,
    name: catalogModel.name,
    cost: catalogModel.cost,
    contextWindow: catalogModel.contextWindow,
    maxTokens: catalogModel.maxTokens,
    input: catalogModel.input,
    reasoning: catalogModel.reasoning,
  };
}

/**
 * Char budget for the transcript sent to the provider. DeepSeek's 1M-token
 * window still dies on a request the API rejects for size, and the sidecar's
 * only other guard is the 200-message cap, so very long sessions are pruned
 * here instead of failing the run.
 */
export const MAX_CONTEXT_CHARS = 400_000;
/** Number of trailing messages that pruneMessages() always keeps. */
export const PRUNE_KEEP_TAIL = 10;

function messageChars(message: AgentMessage): number {
  try {
    return JSON.stringify(message)?.length ?? 0;
  } catch {
    return 0;
  }
}

/**
 * Drop the oldest user/assistant turns until the transcript fits `maxChars`.
 *
 * System messages (prompt + tool declarations) and the last PRUNE_KEEP_TAIL
 * messages are never dropped, so the current question and its tool results
 * always survive. Pure so it can be unit-tested.
 */
export function pruneMessages(
  messages: AgentMessage[],
  maxChars: number = MAX_CONTEXT_CHARS,
): AgentMessage[] {
  let total = 0;
  for (const message of messages) total += messageChars(message);
  if (total <= maxChars) return messages;

  const prunableTail = Math.max(0, messages.length - PRUNE_KEEP_TAIL);
  const dropped = new Set<number>();
  for (let i = 0; i < prunableTail && total > maxChars; i += 1) {
    const role = messages[i].role;
    if (role !== "user" && role !== "assistant") continue; // keep system/toolResult
    dropped.add(i);
    total -= messageChars(messages[i]);
  }
  if (dropped.size === 0) return messages;
  return messages.filter((_message, index) => !dropped.has(index));
}

/**
 * Default factory: registers a per-run OpenAI-compatible provider pointed at
 * the provider's base URL and runs a pi Agent over it. Request apiKey wins;
 * DEEPSEEK_API_KEY / GLM_API_KEY seed the provider auth when the body omits
 * it.
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
          auth: { apiKey: input.apiKey || envApiKeyFor(input.provider) || undefined },
        }),
      },
    },
    models: [resolveModelDefinition(input.provider, input.baseUrl, input.model)],
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
      tools: input.tools,
    },
    // Only forwarded when the caller has a durable session id: it is what
      // lets the provider cache-reuse the replayed transcript prefix.
      ...(input.sessionId ? { sessionId: input.sessionId } : {}),
      streamFn: (modelArg, context, options) =>
      models.streamSimple(modelArg, context, {
          ...options,
          fetch: proxyFetchFor(input.proxy) ?? options?.fetch,
        }),
    // Guard against a transcript the provider would reject outright.
    transformContext: async (messages: AgentMessage[]): Promise<AgentMessage[]> =>
      pruneMessages(messages, MAX_CONTEXT_CHARS),
    // No convertToLlm override on purpose: pi-agent-core's default converter
    // keeps the leading `system` message, which is the ONLY carrier of the
    // system prompt and of the folded tool declarations (`toolsAdded`).
    // A role filter here (user/assistant/toolResult only) silently dropped it,
    // so requests went out with no system message and n_tools=0.
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
  // zhipu / deepseek keep env defaults. Any other provider is treated as
  // OpenAI-compatible (official, relay, or self-hosted) and must send baseUrl.
  const providerError = applyProviderDefaults(params);
  if (providerError) {
    sendJson(res, 400, { error: { code: "invalid_request", message: providerError } });
    return;
  }

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
    const envHint = params.provider === "deepseek"
      ? "DEEPSEEK_API_KEY"
      : params.provider === "zhipu"
        ? "GLM_API_KEY"
        : "request apiKey";
    sendError(
      "invalid_request",
      `${envHint} (or request apiKey) is required to start a run`,
    );
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
      proxy: params.proxy,
      sessionId: params.sessionId,
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
