# M2 试点：dsh 通道 + 隔离分支 + 补丁 + 修复说明

> 2026-11-18 · 状态：**dsh 通道试点完成**；夜间常驻规则未启用（按任务手动触发）

## 缺陷

E2E 发现：助手会话重载后，assistant 消息的 `thinking` 分片与 `text` 一起拼进气泡，
回答前会出现整段英文推理（见 `frontend-pro/tools/e2e/screenshots/03-assistant-chat.png`）。

## dsh 运行

| 项 | 值 |
|---|---|
| 运行时 | DeepSeek Harness `@deepseek-ai/dsh@0.2.2-rc.1`（安装在 241 `/home/openclaw/node_modules`） |
| 模式 | `dsh headless --json --permission-mode workspace-write --approval-policy never` |
| 仓库 | 241 上从 master bundle 克隆的隔离副本 `/home/openclaw/repos/superwork-m2` |
| 会话 | `session-ec809e24-3f0a-4dee-957c-39a36c18dab7`，18 步，22546 tokens，`completed` |
| 分支 | `fix/dsh-thinking`（基于 master `133a6a3`） |
| 提交 | `6bfe180`，只改 `ai-assistant/index.tsx`（+21/−9） |
| 补丁 | `docs/m2/dsh-thinking.patch` |
| 人工复核 | 补丁应用到 `133a6a3` 得 `c28c37e`；frontend-pro 173/173 通过、build 成功；master 零改动 |
| 修复说明 | `artifact 21`（FIX_NOTE，IN_REVIEW），证据 `fact 71` |

修复内容：`extractText` 只收 `type=text` 分片；新增 `extractThinking` 把 thinking 放回
`ChatItem.thinking` 的折叠区，与实时流展示一致。

## 运行边界

- dsh 的模型凭据通过一次性环境变量 `DEEPSEEK_API_KEY` 注入，运行结束即删除，不落盘、不进仓库；
- **没有配置常驻的夜间 dsh 规则**：当前按任务手动触发，避免同一缺陷每晚重复产出；
  需要常驻时，在 `~/.agent-autoloop/rules.json` 增加 `use:"dsh"` 的 agent 与规则即可；
- 另一条已合并到 master 的隔离分支是 `fix/assistant-thinking-render`（含独立单测），
  两者修的是同一处；以 dsh 分支作为 M2 通道证据。

## 应用方式

```bash
git apply docs/m2/dsh-thinking.patch
# 或
git checkout fix/dsh-thinking
```
