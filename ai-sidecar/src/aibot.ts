/**
 * 企微智能机器人长连接桥。
 *
 * 用户在企微里给机器人发的文本消息，经长连接收到后转发到后端
 * POST /internal/opportunity/reply（X-Sidecar-Token 鉴权），由后端按
 * 「发送人 + 24 小时内最新一条待回复问句」自动匹配并直接落库；随后回一句「已自动写入合拍」，
 * 带上解析出的成交概率 / 下次跟进时间。
 *
 * 设计原则：任何失败只打 console.warn，绝不抛错、绝不影响 ai-sidecar 现有 HTTP 能力。
 */

/** 凭据端点最多尝试次数 */
const MAX_CREDENTIAL_ATTEMPTS = 10;
/** 凭据端点重试间隔 */
const CREDENTIAL_RETRY_MS = 30_000;
/** 请求后端超时 */
const REQUEST_TIMEOUT_MS = 10_000;
/** 已处理 msgid 集合上限，防止长跑内存增长 */
const SEEN_MSGID_LIMIT = 2000;
/** 后端未匹配到待回复问句时的回话 */
const UNMATCHED_REPLY =
  "没找到对应的待回复问句（可能已超 24 小时或已在站内处理），可以去合拍里手工补录。";
/** 后端已自动落库但没解析出成交概率/下次跟进时间时的回话 */
const MATCHED_REPLY_NO_PARSED =
  "已收到并写入合拍（未识别到成交概率/下次跟进时间，可在商机详情里补）。";
/**
 * SDK 包名走变量形式，配合动态 import：
 * 依赖由部署侧 npm install 安装，若尚未装好，sidecar 仍能正常启动（只警告不崩）。
 */
const AIBOT_SDK_PACKAGE = "@wecom/aibot-node-sdk";

/**
 * 长连接帧结构（按 SDK dist/types.d.ts 精简而来）。
 * SDK 未稳定导出 WsFrame 类型，这里用本地结构化类型代替，
 * 避免升级小版本时因类型导出缺失导致编译失败。
 */
export interface WsFrame {
  cmd?: string;
  headers?: { req_id?: string };
  body?: {
    msgid?: string;
    msgtype?: string;
    text?: { content?: string };
    /** 引用回复时企微带上的被引用消息（quote.text.content 是问句原文） */
    quote?: { msgtype?: string; text?: { content?: string } };
    chatid?: string;
    from?: { userid?: string };
  };
}

/** SDK WSClient 的最小结构化接口（SDK 类型未稳定导出，用 any 兜底参数） */
interface AibotWsClient {
  connect(): void;
  disconnect(): void;
  on(event: string, handler: (payload: any) => void): unknown;
  reply(frame: WsFrame, payload: unknown, ...rest: any[]): unknown;
  /** 被动回复唯一可用形态：msgtype 只支持 stream（SDK index.cjs.js:1034） */
  replyStream(frame: WsFrame, streamId: string, content: string, finish: boolean): unknown;
}

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

/** 读布尔型环境变量：只有 "true" / "1" 才算开启 */
function envFlag(name: string): boolean {
  const raw = (process.env[name] ?? "").trim().toLowerCase();
  return raw === "true" || raw === "1";
}

/** 后端地址（去掉尾部斜杠，默认容器内网地址） */
function backendBaseUrl(): string {
  const raw = (process.env.BACKEND_BASE_URL ?? "").trim().replace(/\/+$/, "");
  return raw.length > 0 ? raw : "http://backend:8081";
}

/** 用于 X-Sidecar-Token 的项目上下文令牌；为空也照发（由后端判定） */
function sidecarToken(): string {
  return (process.env.PROJECT_CONTEXT_TOKEN ?? "").trim();
}

/** 从后端拉取机器人凭据；失败返回 null，不抛错 */
export async function fetchCredentials(): Promise<{ botId: string; secret: string } | null> {
  const url = `${backendBaseUrl()}/internal/wecom/aibot-credentials`;
  try {
    const res = await fetch(url, {
      method: "GET",
      headers: { "X-Sidecar-Token": sidecarToken() },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (!res.ok) {
      console.warn(`[aibot] 获取凭据失败：HTTP ${res.status}`);
      return null;
    }
    const json: unknown = await res.json().catch(() => null);
    // 兼容两种返回：{botId,secret} 与 {data:{botId,secret}}
    const root = json as { data?: unknown; botId?: unknown; secret?: unknown } | null;
    const holder = (root?.data ?? root) as { botId?: unknown; secret?: unknown } | null;
    const botId = typeof holder?.botId === "string" ? holder.botId.trim() : "";
    const secret = typeof holder?.secret === "string" ? holder.secret.trim() : "";
    if (botId.length === 0 || secret.length === 0) {
      console.warn("[aibot] 获取凭据失败：响应缺少 botId 或 secret");
      return null;
    }
    return { botId, secret };
  } catch (err) {
    console.warn("[aibot] 获取凭据异常：", err);
    return null;
  }
}

/** 启动时拉取凭据：失败 30s 后重试，最多 10 次；全失败只警告 */
async function loadCredentials(): Promise<{ botId: string; secret: string } | null> {
  for (let attempt = 1; attempt <= MAX_CREDENTIAL_ATTEMPTS; attempt += 1) {
    const credentials = await fetchCredentials();
    if (credentials) return credentials;
    if (attempt < MAX_CREDENTIAL_ATTEMPTS) {
      console.warn(`[aibot] 凭据获取失败（第 ${attempt}/${MAX_CREDENTIAL_ATTEMPTS} 次），${CREDENTIAL_RETRY_MS / 1000}s 后重试`);
      await sleep(CREDENTIAL_RETRY_MS);
    }
  }
  console.warn(`[aibot] 凭据连续 ${MAX_CREDENTIAL_ATTEMPTS} 次获取失败，放弃启动长连接（不影响 AI 助手）`);
  return null;
}

/** 已处理的 msgid 集合：通用 message 与 message.text 可能同时投递同一帧，靠它去重 */
const seenMsgIds = new Set<string>();

/** 标记 msgid 已处理；返回 false 表示重复投递 */
export function markSeen(msgId: string): boolean {
  if (seenMsgIds.has(msgId)) return false;
  seenMsgIds.add(msgId);
  if (seenMsgIds.size > SEEN_MSGID_LIMIT) {
    const oldest = seenMsgIds.values().next();
    if (!oldest.done) seenMsgIds.delete(oldest.value);
  }
  return true;
}

/** 后端回传的解析字段：成交概率（0-100）与下次跟进时间 */
interface ParsedReplyFields {
  probability?: number;
  nextAt?: string;
}

/** 从 Result.data.parsedJson 里取解析值；取不到返回 null（按「未识别到」处理，不报错） */
export function readParsedFields(payload: unknown): ParsedReplyFields | null {
  const data = (payload as { data?: unknown } | null)?.data;
  if (data === null || typeof data !== "object" || Array.isArray(data)) return null;
  const raw = (data as { parsedJson?: unknown }).parsedJson;
  if (typeof raw !== "string" || raw.trim().length === 0) return null;
  try {
    const parsed = JSON.parse(raw) as { probability?: unknown; nextAt?: unknown };
    const fields: ParsedReplyFields = {};
    const probability = parsed?.probability;
    if (typeof probability === "number" && Number.isFinite(probability)) {
      fields.probability = probability;
    }
    const nextAt = parsed?.nextAt;
    if (typeof nextAt === "string" && nextAt.trim().length > 0) {
      fields.nextAt = nextAt;
    }
    return fields.probability === undefined && fields.nextAt === undefined ? null : fields;
  } catch (err) {
    console.warn("[aibot] parsedJson 解析失败：", err);
    return null;
  }
}

/** ISO 时间（2026-12-01T15:00:00）→「2026-12-01 15:00」 */
function formatNextAt(value: string): string {
  return value.trim().replace("T", " ").slice(0, 16);
}

/** 匹配到问句时的回话：有解析值就带上，没有就提示去商机详情补 */
export function matchedReplyText(parsed: ParsedReplyFields | null): string {
  if (!parsed) return MATCHED_REPLY_NO_PARSED;
  const parts: string[] = [];
  if (typeof parsed.probability === "number") parts.push(`成交概率 ${parsed.probability}%`);
  if (typeof parsed.nextAt === "string") {
    const when = formatNextAt(parsed.nextAt);
    if (when.length > 0) parts.push(`下次跟进 ${when}`);
  }
  return parts.length === 0 ? MATCHED_REPLY_NO_PARSED : `已写入合拍：${parts.join("，")}`;
}

/** 转发结果：matched=后端匹配到问句，parsed=后端解析出的概率/下次跟进时间 */
interface ReplyOutcome {
  matched: boolean;
  parsed: ParsedReplyFields | null;
}

/** 判断后端是否匹配到待回复问句：data 非空才算匹配（为空是正常情况，不重试） */
function hasMatchedData(payload: unknown): boolean {
  const data = (payload as { data?: unknown } | null)?.data;
  if (data === null || data === undefined) return false;
  if (Array.isArray(data)) return data.length > 0;
  if (typeof data === "object") return Object.keys(data as Record<string, unknown>).length > 0;
  if (typeof data === "string") return data.trim().length > 0;
  return true;
}

/**
 * 把一条文本消息转发到后端。
 * 返回 {matched,parsed}=请求成功（matched false 表示没匹配到问句），null=请求本身失败（此时不回话，避免误导用户）。
 */
async function forwardToBackend(frame: WsFrame, text: string): Promise<ReplyOutcome | null> {
  const url = `${backendBaseUrl()}/internal/opportunity/reply`;
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Sidecar-Token": sidecarToken(),
      },
      body: JSON.stringify({
        senderKey: frame.body?.from?.userid,
        text,
        msgId: frame.body?.msgid,
        chatId: frame.body?.chatid,
        // 引用回复带上被引用问句原文：后端靠它区分「同一人同一天的多条问句」
        quotedText: frame.body?.quote?.text?.content,
        channel: "WECOM_BOT",
      }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (!res.ok) {
      console.warn(`[aibot] 入站转发失败：HTTP ${res.status}`);
      return null;
    }
    const json: unknown = await res.json().catch(() => null);
    return { matched: hasMatchedData(json), parsed: readParsedFields(json) };
  } catch (err) {
    console.warn("[aibot] 入站转发异常：", err);
    return null;
  }
}

/** SDK 导出的 generateReqId（若有），用于生成流式回复的 streamId */
let sdkGenerateReqId: ((prefix: string) => string) | null = null;

/** 生成 streamId：优先用 SDK 的 generateReqId('stream')，拿不到就本地生成 */
function genStreamId(): string {
  try {
    if (typeof sdkGenerateReqId === "function") {
      const id = sdkGenerateReqId("stream");
      if (typeof id === "string" && id.trim().length > 0) return id.trim();
    }
  } catch (err) {
    console.warn("[aibot] 生成 streamId 失败，改用本地 ID：", err);
  }
  return `stream_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 回话给企微用户。
 *
 * 被动回复的 msgtype 只接受 stream：旧写法 reply(frame, { msgtype: "text" }) 会被企微
 * 以 errcode 40008 invalid message type 拒绝（见 SDK index.cjs.js:1034 与 replyStream 便捷方法）。
 * 这里先发一帧未结束的内容，再发一帧 finish=true 收尾。
 */
async function replySafely(client: AibotWsClient, frame: WsFrame, content: string): Promise<void> {
  try {
    if (typeof client.replyStream !== "function") {
      console.warn("[aibot] SDK 未提供 replyStream，跳过回话");
      return;
    }
    const streamId = genStreamId();
    await Promise.resolve(client.replyStream(frame, streamId, content, false));
    await Promise.resolve(client.replyStream(frame, streamId, content, true));
  } catch (err) {
    console.warn("[aibot] 回话失败：", err);
  }
}

/** 处理一条文本消息：转发后端（后端已自动落库）→ 按匹配结果回话 */
async function handleTextMessage(client: AibotWsClient, frame: WsFrame): Promise<void> {
  const content = frame.body?.text?.content;
  if (typeof content !== "string" || content.trim().length === 0) return;
  const outcome = await forwardToBackend(frame, content);
  if (outcome === null) return;
  await replySafely(client, frame, outcome.matched ? matchedReplyText(outcome.parsed) : UNMATCHED_REPLY);
}

/** 统一的帧入口：先去重，再只处理文本消息 */
export function onFrame(client: AibotWsClient, frame: WsFrame): void {
  try {
    const msgId = frame?.body?.msgid;
    if (typeof msgId !== "string" || msgId.length === 0) {
      console.warn("[aibot] 收到缺少 msgid 的帧，已忽略");
      return;
    }
    if (!markSeen(msgId)) return;
    if (frame.body?.msgtype !== "text") return;
    void handleTextMessage(client, frame).catch((err: unknown) => {
      console.warn("[aibot] 处理文本消息异常：", err);
    });
  } catch (err) {
    console.warn("[aibot] 帧处理异常：", err);
  }
}

/** 拉凭据 → 建长连接 → 注册事件监听 */
async function startClient(): Promise<void> {
  const credentials = await loadCredentials();
  if (!credentials) return;

  const mod: any = await import(AIBOT_SDK_PACKAGE);
  const AiBot = mod?.default ?? mod;
  const generateReqId = (AiBot as { generateReqId?: unknown } | null)?.generateReqId
    ?? (mod as { generateReqId?: unknown } | null)?.generateReqId;
  if (typeof generateReqId === "function") {
    sdkGenerateReqId = generateReqId as (prefix: string) => string;
  }
  const WSClient = AiBot?.WSClient;
  if (typeof WSClient !== "function") {
    console.warn("[aibot] SDK 未提供 WSClient，跳过长连接启动");
    return;
  }

  const client: AibotWsClient = new WSClient({
    botId: credentials.botId,
    secret: credentials.secret,
  });

  client.on("authenticated", () => console.info("[aibot] 长连接鉴权成功"));
  client.on("disconnected", () => console.warn("[aibot] 长连接已断开，等待 SDK 重连"));
  client.on("error", (err: unknown) => console.warn("[aibot] 长连接错误：", err));
  // 专用事件：文本消息
  client.on("message.text", (frame: WsFrame) => onFrame(client, frame));
  // 容错：部分 SDK 版本只发通用 message 事件，这里再挂一次，重复帧由 msgid 去重挡住
  client.on("message", (frame: WsFrame) => onFrame(client, frame));

  client.connect();
  console.info("[aibot] 企微智能机器人长连接启动中");
}

/**
 * 启动企微智能机器人长连接桥。
 * AIBOT_ENABLED 未开启时直接返回；任何异常只打日志，绝不 crash 进程。
 */
export function startAibotBridge(): void {
  if (!envFlag("AIBOT_ENABLED")) return;
  try {
    void startClient().catch((err: unknown) => {
      console.warn("[aibot] 启动长连接失败：", err);
    });
  } catch (err) {
    console.warn("[aibot] 启动长连接失败：", err);
  }
}
