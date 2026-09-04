# AI Agent Sidecar 集成契约 v1

架构:Vue 前端 ⇄ Spring Boot(鉴权/会话持久化/工具执行)⇄ ai-sidecar(Node 22,pi-agent-core + pi-ai)⇄ LLM。

## Sidecar HTTP API

### GET /healthz
200 `{"ok":true,"version":"0.1.0"}`

### POST /v1/runs
请求体 application/json;响应为 SSE(`Content-Type: text/event-stream`),事件以 `event:` + `data:` 分帧。

鉴权:请求头 `X-Sidecar-Token` 必须等于环境变量 `SIDECAR_TOKEN`(若该变量未设置则跳过校验);不匹配返回 401 JSON。
```json
{
  "runId": "Java 生成的 uuid",
  "provider": "zhipu",
  "baseUrl": "https://open.bigmodel.cn/api/paas/v4",
  "apiKey": "sk-…",
  "model": "glm-5.3",
  "thinkingLevel": "max",
  "systemPrompt": "…",
  "messages": [],
  "tools": [
    {"name": "query_my_tasks", "description": "…", "parameters": {}}
  ],
  "toolCallbackUrl": "http://backend:8080/internal/ai-agent/tools"
}
```
provider 支持 `zhipu`（GLM，智谱）与 `deepseek`；其他值返回 400。
baseUrl / apiKey 可由请求传入；缺省时 sidecar 按 provider 取环境变量：
`zhipu` → `ZHIPU_BASE_URL`（默认 https://open.bigmodel.cn/api/paas/v4）/ `GLM_API_KEY`；
`deepseek` → `DEEPSEEK_BASE_URL`（默认 https://api.deepseek.com）/ `DEEPSEEK_API_KEY`。
thinkingLevel 默认 max。

`messages` 为 pi-agent-core 的 AgentMessage JSON 数组(旧→新,最后一条是本轮 user 消息);Java 仅视为不透明结构并原样持久化/回放,文本展示依赖 sidecar 发出的 SSE 事件。

SSE 事件:
- `run_start` {runId}
- `message_start` {index}
- `message_delta` {index, delta:{"type":"text_delta"|"thinking_delta","text":"…"}}
- `message_end` {message: AgentMessage}
- `tool_execution_start` {toolCallId, toolName, args}
- `tool_execution_end` {toolCallId, result, isError}
- `run_end` {newMessages: [AgentMessage…]}(仅本轮新增消息,不含请求中的历史;Java 据此持久化)
- `error` {code, message}(随后流必须结束)

事件顺序:`run_start` → (message_start/message_delta/message_end | tool_execution_start/tool_execution_end)* → `run_end`。Java 将事件原样转发给前端。

限制:单次 run 最长 600 秒,超时发 `error` 并结束;`messages` 上限 200 条;单次工具回调超时 120 秒。

## 工具回调(sidecar → Java)
POST {toolCallbackUrl},头 `X-Sidecar-Token`(同上)、`Content-Type: application/json`,体 {"runId","toolName","args"}。
- 200 {"content":"…","isError":false} 视为成功
- 200 {"content":"…","isError":true} 或任何非 2xx 均视为工具失败(isError=true)
- args 由模型生成,Java 端必须自行校验,不得假设字段存在
