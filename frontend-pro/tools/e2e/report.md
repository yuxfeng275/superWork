# 241 E2E 报告

- 地址：http://192.168.1.241:18080
- 账号：e2e_admin
- 开始：2026-11-17T18:14:14.369Z
- 结束：2026-11-17T18:15:16.606Z
- 结果：全部通过

| 步骤 | 结果 | 说明 | 截图 |
|---|---|---|---|
| 登录 | ✅ | HTTP 200，用户 e2e_admin |  |
| Jev 会话事件 | ✅ | ready=false；Jev 调用失败，已 fail-open：TypeSafe Jev暂时不可用(451) |  |
| 工作台打开 | ✅ | 待我评审 + 今日晨报可见 | 01-workbench.png |
| 建议去做 | ✅ | 预填 120 字 | 02-suggestion-draft.png |
| 助手对话 | ✅ | Jev 标签可见；fail-open 提示=true；气泡 0→4 | 03-assistant-chat.png |
| 知识库检索 | ✅ | 检索「会员通 交付周期」；命中 1 条；首条：会员通交付说明 相关度 8.8430 会员通产品支持全渠道会员统一识别、积分通兑、等级体系。交付周期一般为 6-8 周， | 04-knowledge.png |
| 团队效能 | ✅ | 10 行数据 | 05-team-efficiency.png |
| 制品评审 | ✅ | 详情抽屉已打开 | 06-artifact-review.png |

## Jev 会话事件

- ready=false；Jev 调用失败，已 fail-open：TypeSafe Jev暂时不可用(451)

## 服务端错误

无

## 非 2xx API 调用

无

## 控制台告警（不计入失败）

共 5 条，首条：plugin-model: Invoking 'index' model failed: Error
