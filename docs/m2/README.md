# M2 试点：一个缺陷的隔离分支 + 补丁 + 修复说明

> 2026-11-18 · 状态：**手工试点完成**（夜间 dsh 运行时未接：241 无 dsh/CLI agent，仓库无绑定凭据）

## 缺陷

E2E 发现：助手会话重载后，assistant 消息的 `thinking` 分片与 `text` 一起拼进气泡，
回答前会出现整段英文推理（见 `frontend-pro/tools/e2e/screenshots/03-assistant-chat.png`）。

根因：`ai-assistant/index.tsx` 的 `extractText` 把 `part.thinking` 当作正文返回；
流式阶段虽然把 `thinking_delta` 放进了 Think 区，但历史消息重建时又合并了。

## 产物

| 项 | 值 |
|---|---|
| 隔离分支 | `fix/assistant-thinking-render` |
| 分支提交 | `2a1a0a2`（基于 master `f8d61d8`） |
| 补丁 | `docs/m2/assistant-thinking-render.patch`（152 行） |
| 改动 | 新增 `messages.ts` 拆分 text/thinking；重建会话时 thinking 进 Think 折叠区；3 例单测 |
| 验证 | 前端 176/176 通过、`npm run build` 成功 |
| 主分支 | **代码零改动**（只有本目录的文档与补丁） |
| 项目事实 | `fact 71`（系统缺陷记录） |
| 修复说明制品 | `artifact 20`，FIX_NOTE，IN_REVIEW（项目 1 评审队列可见） |

## 应用方式

```bash
git checkout fix/assistant-thinking-render
# 评审通过后合并；或
git apply docs/m2/assistant-thinking-render.patch
```

## 边界

- 这是 **隔离分支 + 补丁 + 修复说明** 的手工试点，用来验证 M2 的产出契约；
- **夜间自动化没有接**：241 上没有 dsh/CLI agent，`project_git_repo` 也没有绑定仓库与凭据；
- 等提供仓库凭据与 agent 运行时后，把同一契约接到 autoloop 的 command 通道即可。
