# 企业微信接入方案（AI 助手成为日常工作入口）V1 落地设计

> 依据：《企业微信集成能力调研报告》（全部端点已对照官方文档逐页核实）。
> 愿景：企微不只是"又一个连接器"，而是**消息入口 + 身份底座 + 触达通道**三合一，让用户在企微里就能用上 AI 助手和全部连接器能力。

## 一、最终形态（目标架构）

```
企微用户 ──单聊/群@──▶ 智能机器人(WebSocket长连接, Node sidecar)
                          │  userid ↔ 本地用户 (email_wecom_mapping)
                          ▼
                    AI 助手内核（现有 SSE 对话 + 全部连接器工具）
                          │
              ┌───────────┼────────────┐
        OA致远         云效         邮箱/工时/语雀…   ← 已接入
        审批API◀──企微V1──企微通讯录同步
```

## 二、V1 实施（全部 REST 出站，约 5–7 人日，零新基建）

### 1. WeComClient 增强（0.5 人日，先行）
- `gettoken` 结果缓存（corpid+secret 维度，expires_in 提前 5 分钟刷新）——现实现每次 push 现取 token，会撞限频
- 泛化 `sendText` / `sendMarkdown`（markdown ≤2048 字节，超长截断）
- 所有调用入口记录"可信IP"前提：服务器出口 IP 须在企微后台配置

### 2. 通讯录同步 + 自动绑定（2–3 人日）
- 定时任务（每日一次 + 手动触发按钮）：`department/list` 递归 → `user/list`（含 mobile）拉全组织
- 与本地 `user` 表按 **手机号/邮箱** 匹配 → 写 `email_wecom_mapping`（复用现有表，推送链路已依赖）
- 兜底：单人 `POST /cgi-bin/user/getuserid`（mobile→userid）；**失败冷却**：错误次数超企业人数 20% 会被封禁 1 天，必须熔断
- 产出：本地用户 ↔ 企微 userid 全量映射，所有触达/回调的身份翻译就绪

### 3. OAuth 免登绑定（2 人日）
- AI 助手页面检测到企微内置浏览器（UA 含 wxwork）→ 302 `connect/oauth2/authorize?scope=snsapi_base` → `auth/getuserinfo` 换 userid → 静默补齐映射（snsapi_base 无需用户确认）
- 域名须在企微后台配置为应用「可信域名」（http://192.168.1.241:18080 若为 IP 则需用域名方案，注意 50001 错误码）
- 兜底入口：邮箱管理里保留手工填 wecomUserId

### 4. 审批只读 AI 工具（2 人日）
- `POST /cgi-bin/oa/getapprovalinfo`（按 template/creator/时间窗过滤，单次≤100、跨度≤31 天）+ `getapprovaldetail`
- 注册为 AI 工具：`query_wecom_approvals`（我的审批单列表）+ `read_wecom_approval`（单据详情）——不走 GenericConnectorToolService 的简单 query/read 模型，保留专用 Service（审批入参是结构化 JSON filters，与 SEEYON 同理）
- 限频 600/分，内部用量充足；管理员需在企微后台把自建应用加入「审批-可调用接口的应用」并授权 API 数据权限

### 5. 主动触达增强（0.5 人日，搭车）
- 审批状态变化回调（91815）+ 定时拉取兜底：单据被驳回/通过时 `message/send` markdown 通知本人
- 每日工时缺填提醒、邮件摘要走 markdown（现有 push 升级）

## 三、V2 实施（对话入口，约 4–6 人日）

### 6. 智能机器人 · WebSocket 长连接（3–4 人日）★ 愿景核心
- **选长连接而非传统回调**：`wss://openws.work.weixin.qq.com`，仅需 BotID + 长连接专用 Secret，**无需公网 URL、无需加解密**（241 无公网入口的约束被消除）
- Node sidecar 引入 `@wecom/aibot-node-sdk`（官方），事件流：`aibot_msg_callback`（单聊/群@）→ 调 Spring 内部接口发起 AI run（复用现有 runId→userId + 工具回调链）→ `aibot_respond_msg` 流式回复（与 SSE 形态对齐，最长 6 分钟）
- 注意：`from.userid` 明文要求机器人创建者为超级管理员，否则补 `batch/openuserid_to_userid` 转换；单机器人仅 1 条长连接（新踢旧），多实例需主备

### 7. 可选：自建应用回调路线 A（3–5 人日，按需）
- 仅当需要「应用会话内被动回复 + 模板卡片按钮交互（审批卡片同意/驳回）」时做
- 握手清单见调研报告 §四（WXBizMsgCrypt、5s 应答、MsgId 排重、空串+异步回复）

## 四、与现有框架的融合

| 项 | 决策 |
|---|---|
| 认证类型 | `ai_connector.auth_type` 新增 `WECOM`（corpid+secret→动态 token 两段式，与 SEEYON 同类）；V52 的枚举校验同步放开；预置 wecom 内置连接器行 |
| 客户端 | `GenericConnectorClient` 对 WECOM 先取缓存 token；`WeComClient` 升级为共享 token 缓存组件，推送/通讯录/审批共用 |
| 身份映射 | 统一用 `email_wecom_mapping`（推送链路已依赖），OAuth/机器人绑定都写这张表；`ai_connector_identity` 不用于企微，避免双表歧义 |
| 工具 | 审批用专用 Service + 专用工具（结构化入参）；通讯录触达不暴露为 LLM 工具（隐私） |
| 消息回复 | V2 机器人长连接挂 Node sidecar，Spring 侧仅暴露内部 HTTP（沿用现有 tool callback 流） |

## 五、风险与前提

1. **可信IP**：自建应用调 API 必须在后台录入服务器出口 IP（2022 起强制），IP 变更即全断——上线前必须配置
2. **权限全靠管理后台人工**：通讯录可见范围、审批 API 数据权限、可信域名/机器人创建——需企微管理员配合，排期留缓冲
3. **手机号封禁**：getuserid 错误 >企业人数 20% 封 1 天——同步任务必须失败冷却+熔断
4. **回调可靠性**（若走路线 A）：5s 超时重试 3 次，官方明示勿强依赖，审批状态以拉取为主、回调为加速
5. **能力边界要管理预期**：日程/会议只能读应用自建数据；无用户级 OAuth API token（不能"以用户身份"读其全部文档）；无官方 MCP server（REST 直连，未来官方出 MCP 可无缝挂载为 auth_type=MCP）
6. **markdown 尺寸**：应用消息 ≤2048 字节，AI 长回答需摘要/分段

## 六、实施顺序与工作量汇总

| 阶段 | 内容 | 人日 | 依赖 |
|---|---|---|---|
| V1.1 | WeComClient token 缓存 + markdown | 0.5 | 无 |
| V1.2 | 通讯录同步 + 手机号绑定 | 2–3 | V1.1, 企微后台权限 |
| V1.3 | OAuth 免登绑定 | 2 | 可信域名（域名方案或 IP 特例） |
| V1.4 | 审批只读 AI 工具 | 2 | 审批 API 数据权限 |
| V1.5 | 主动触达（驳回提醒/摘要 markdown） | 0.5 | V1.2 |
| **V1 合计** | | **5–7** | |
| V2.1 | 智能机器人长连接（对话入口） | 3–4 | 机器人创建（超管） |
| V2.2 | 审批卡片交互（可选路线 A） | 3–5 | 公网回调或保持长连接 |
| **V2 合计** | | **4–6** | |

> V1 完成后：AI 助手「认得每个用户、能查每个人的审批、能主动触达任何人」——数据中心就绪。
> V2 完成后：用户在企微单聊/群 @机器人即可直接对话 AI 助手——**日常工作入口**愿景闭环。
