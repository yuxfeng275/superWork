# 241 E2E 运行说明

```bash
E2E_BASE=http://192.168.1.241:18080 E2E_PASSWORD=*** \
  node frontend-pro/tools/e2e/run.mjs
```

## 前置（只影响测试账号 admin）

1. **建议条可重复**：`/api/ai-agent/proactive` 的建议点一次「去做」会变 DONE。
   重跑前把测试账号的建议重置为 NEW（仅测试账号，不碰其他用户）：
   ```sql
   UPDATE ai_proactive_suggestion SET status='NEW' WHERE id IN (53,54);
   ```
2. **晨报 fixture**：`ai_brief` id 15 是 projectId=0 的跨项目 E2E 晨报（内容以「E2E 验证」开头），
   不属于任何真实项目；工作台「今日安排」读取它，用来断言晨报可见。
3. 不触发真实企微群发/邮件外发；登录走 API + localStorage 注入，不在页面输入密码。

## 产物

- `report.md` / `report.json`：步骤、断言说明、截图、Jev 事件、服务端错误
- `network.json`：所有 `/api/` 请求的方法/状态/路径
- `screenshots/`：工作台、建议预填、助手对话、知识库、团队效能、制品评审
