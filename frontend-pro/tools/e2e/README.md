# 241 E2E 运行说明

```bash
E2E_BASE=http://192.168.1.241:18080 E2E_USER=e2e_admin E2E_PASSWORD=*** \
  node frontend-pro/tools/e2e/run.mjs
```

## 前置（只影响专用测试账号 e2e_admin，不是生产 admin）

1. **建议条可重复**：`/api/ai-agent/proactive` 的建议点一次「去做」会变 DONE。
   重跑前执行 `prepare.sql`，只重置 e2e_admin 的 fixture（dedupe_key=e2e-fixture-2026-11-18）。
2. **晨报 fixture**：`ai_brief` id 16 是 e2e_admin 的 projectId=0 跨项目晨报（内容以「E2E 验证」开头），
   不属于任何真实项目；工作台「今日安排」读取它，用来断言晨报可见。
3. 不触发真实企微群发/邮件外发；登录走 API + localStorage 注入，不在页面输入密码。

## 产物

- `report.md` / `report.json`：步骤、断言说明、截图、Jev 事件、服务端错误
- `network.json`：所有 `/api/` 请求的方法/状态/路径
- `screenshots/`：工作台、建议预填、助手对话、知识库、团队效能、制品评审

测试账号 `e2e_admin`（DIRECTOR）由管理员创建，密码只从环境变量读；生产 admin 密码已在 2026-11-18 轮换，不再写入仓库。
