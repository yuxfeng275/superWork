# brainstorm: 邮件收取与每日摘要

## Goal

在现有 BU 管理系统中增加邮件管理能力：安全连接业务邮箱、持续收取邮件，并按自然日形成可查看的邮件摘要概览，帮助用户快速识别重要事项、待办、风险和需要回复的邮件。

## What I already know

* 用户明确要求：1）可以收取邮件；2）可以汇总概览每日邮件摘要。
* 项目是 Vue 3 + TypeScript + Element Plus 前端，Spring Boot 3.2 + Java 17 + MyBatis-Plus + MySQL 后端。
* 后端已启用 Spring Scheduling，并有云效/致远 OA 定时同步与连接配置模式，可复用其“配置、连接测试、定时任务、同步状态”结构。
* 数据库通过 Flyway 迁移；所有 API 默认要求 JWT 登录，路由和菜单支持岗位/用户名可见性控制。
* 当前仓库没有邮件收取（IMAP/POP3）或邮件摘要/LLM 集成代码，也没有邮件 SDK 依赖。
* 当前工作区存在大量用户的未提交修改；本任务必须严格限定修改范围，不能覆盖或清理现有变更。

## Assumptions (temporary)

* 已确认：MVP 采用员工个人邮箱模式，每位用户独立绑定并仅查看自己的邮件和摘要。
* 已确认：首期仅支持阿里云企业邮箱，不做通用 Gmail / Microsoft 365 / 任意 IMAP 配置。
* 已确认：员工使用阿里云企业邮箱的第三方客户端安全密码绑定，不使用网页登录密码。
* 已确认：MVP 只同步阿里云企业邮箱的收件箱（Inbox），不读取垃圾邮件、已发送、草稿或自定义文件夹。
* MVP 以“收件与阅读/摘要”为主，不包含发信、回复、转发、删除服务器邮件等写操作。
* 优先采用 IMAP over TLS，按 UID 增量同步，避免重复收取；POP3 仅在确有邮箱限制时再考虑。
* 邮件正文与附件属于敏感数据；凭据不得明文返回前端或写日志，摘要服务应遵循最小数据暴露原则。
* 已确认：摘要采用“大模型智能摘要 + 规则降级”；模型失败、超时或限流不得影响收信，摘要可独立重试。
* 已确认：智能摘要平台采用 DeepSeek；通过 OpenAI 兼容的 Chat Completions 接口接入，`baseUrl`、`model`、API Key 均在邮件管理页面维护并加密保存到数据库。
* 已确认：允许将邮件完整正文转换为纯文本后发送给 DeepSeek；不发送 HTML 源码、远程图片、附件、邮箱凭据或不属于当前用户的邮件。
* 已确认：首次绑定同步最近 7 个自然日（含绑定当天，Asia/Shanghai）的邮件；之后按 IMAP UID 持续增量同步。
* 已确认：摘要除系统页面外，还通过企业微信内部应用点对点推送给对应员工，不使用群机器人。
* 默认决策：企业微信推送“摘要概览 + 重要事项 + 系统详情链接”，完整正文与完整摘要只在登录系统后查看。
* 默认决策：附件首期仅保存并展示文件名、类型、大小等元数据，不下载、不解析、不发送给 DeepSeek。
* 已确认：自动收信每小时执行一次，同时保留“立即同步”入口；连接或解析失败不阻断下一轮。
* 已确认：每日 08:00（Asia/Shanghai）生成前一自然日的最终摘要；统计边界为 00:00:00–23:59:59，生成前先补做一次邮件同步，并允许用户手动补算。

## Open Questions

* None. Remaining implementation details use the documented defaults.

## Requirements (evolving)

* 每位系统用户可以绑定自己的个人邮箱，且只能访问自己的邮箱账户、邮件和每日摘要。
* 用户通过邮箱地址和第三方客户端安全密码绑定；安全密码加密落库、写入后不可回显，日志中不得出现。
* 系统能够安全连接指定邮箱并测试连接状态。
* 系统每小时自动收取一次，并允许用户手动触发自己的邮箱立即同步；手动触发采用异步任务和状态轮询。
* 首次连接只从 Inbox 收取最近 7 个自然日（含绑定当天）的邮件，此后持续增量收取；断线恢复和重复执行不会产生重复记录。
* 系统能够按日展示邮件数量与摘要概览；前一天摘要在次日 08:00 自动生成并作为稳定版本保存。
* 摘要生成成功后，由企业微信内部应用点对点推送给对应员工；推送失败不得影响系统内摘要，且可独立重试。
* 系统维护本地用户与企业微信 userId 的映射；没有有效映射的员工仍可在系统查看摘要，页面明确显示“未推送”。
* 智能摘要至少输出：当日总览、重要邮件、待办事项、风险提醒和建议回复项，并能引用对应邮件 ID 供用户回看。
* 大模型不可用时生成基于发件人、主题、时间与关键词的规则摘要，并明确标注降级状态。
* DeepSeek 摘要请求使用 JSON 输出并在服务端进行结构校验；不合规、截断或缺字段的响应进入失败/降级流程。
* DeepSeek 输入可包含单封邮件的完整正文纯文本；服务端仍需设置单日总输入上限与分批/归并策略，避免超出模型上下文或造成异常成本。
* 单封邮件保留主题、发件人、收件时间、正文预览、已同步状态等必要信息。
* 同步失败可观察、可重试，不阻塞已收取邮件的浏览。

## Acceptance Criteria (evolving)

* [x] 配置正确的邮箱地址和第三方客户端安全密码后，连接测试能返回明确成功状态。
* [x] 查询账户配置时仅返回 `credentialConfigured` 等状态，不返回安全密码或密文。
* [x] 用户 A 无法通过列表、详情或直接构造 API ID 访问用户 B 的邮箱、邮件或摘要。
* [x] 首次绑定只从 Inbox 收取最近 7 个自然日（含绑定当天）的邮件，不收取更早历史或其他文件夹。
* [x] 系统每小时自动同步一次，用户也可手动触发立即同步；两种方式均按 `UIDVALIDITY + UID` 幂等入库。
* [x] 用户可按日期查看当日邮件列表和当日摘要。
* [x] 每日 08:00（Asia/Shanghai）先补同步邮箱，再为当前用户的前一自然日邮件生成摘要。
* [x] 摘要生成后由企业微信内部应用发送给对应员工，不能发到群聊或其他员工；推送失败在页面可见且不影响摘要查看。
* [x] 企业微信凭据只从服务端环境变量读取，接口、日志和页面均不返回 Secret 或 access token。
* [x] 前一日无邮件时显示明确空状态，不调用大模型且不生成误导性摘要。
* [x] DeepSeek 失败、超时、限流或返回不合法 JSON 时，邮件仍正常入库，并展示可用的规则摘要和降级状态。
* [x] 摘要中的重要事项、待办和风险可跳转到对应邮件，无法追溯的内容不得作为确定事实展示。
* [x] 发往 DeepSeek 的载荷不含邮箱安全密码、HTML 源码、附件或其他用户的邮件，并记录不含正文的调用审计元数据。
* [x] 邮箱不可达、认证失败、邮件解析失败、摘要服务失败时均有明确状态且不会泄露凭据。
* [x] 未授权用户无法访问不属于其权限范围的邮箱、邮件正文或摘要。

## Definition of Done

* 后端单元/集成测试与前端关键交互测试新增或更新。
* 后端测试、前端 build/typecheck 通过。
* 数据库迁移、环境变量、部署配置及回滚策略完成。
* 新的后端 API 和前端消费契约写入 `.trellis/spec/`。
* 部署到项目环境并验证部署 URL（若部署凭据与环境可用）。

## Out of Scope (explicit, pending confirmation)

* 发送、回复、转发邮件。
* 修改服务器端已读/未读、移动文件夹、删除邮件。
* 完整附件内容索引、附件 OCR、附件智能问答。
* 除企业微信内部应用外，不支持群机器人、钉钉、邮件、短信等其他摘要推送渠道。

## Technical Notes

* 后端入口与调度：`backend/src/main/java/com/bu/management/BuManagementApplication.java`、`service/WorklogComplianceService.java`、`service/YunxiaoIntegrationService.java`。
* 集成配置模式：`config/YunxiaoProperties.java`、`service/YunxiaoConfigService.java`、`integration/YunxiaoClient.java`。
* 配置来源：DeepSeek 与企业微信业务配置由邮件管理页面维护并加密保存到数据库；仅根加密密钥与 cron 属于基础设施配置。
* 数据迁移：`backend/src/main/resources/db/migration/`。
* 前端入口：`frontend/src/router/index.ts`、`frontend/src/layouts/MainLayout.vue`、`frontend/src/utils/api.ts`。
* 项目目前无 Jakarta Mail/Spring Integration Mail/LLM SDK，需要在方案确认后选择最小依赖。
* 后端访问控制采用 `@RequirePermission` + 数据库权限码；邮件接口需要独立的 `email:view`、`email:manage`、`email:sync` 权限，并在服务层强制邮箱归属/范围过滤。
* 多邮箱建议建立 `email_account`、`email_message`、`email_daily_digest`，以 `(account_id, folder, uid_validity, uid)` 做邮件幂等唯一键；摘要失败不得影响邮件入库。
* 现有调度没有分布式锁；若后端可能多副本运行，邮件同步必须使用数据库租约/锁及持久化检查点，不能只靠 JVM 内 `AtomicBoolean`。
* 生产部署需要允许 IMAPS 993 和摘要模型 HTTPS 出站；手动同步应异步触发并轮询状态，不能长期占用 HTTP 请求。

## Decision (ADR-lite, evolving)

**Context**: 邮箱归属决定凭据存储、数据隔离、调度粒度和权限模型。
**Decision**: 首期采用员工个人邮箱，每位系统用户绑定自己的阿里云企业邮箱账户。
**Consequences**: `email_account` 必须关联 `owner_user_id`；所有邮件与摘要查询均按当前 JWT 用户强制过滤；管理员即使拥有配置权限，也不默认获得邮件正文读取权。安全凭据采用第三方客户端安全密码，通过 AES-256-GCM 加密落库，前端不可回显。

## Research Notes

### Feasible protocol baseline

* IMAP 支持文件夹、UID、已读状态等能力，更适合增量收取与后续扩展；MVP 推荐只读 IMAPS。
* 阿里云企业邮箱作为首期唯一供应商，产品层可提供预置连接参数而不要求普通用户理解服务器地址/端口；部署前仍需用实际租户账号验证 IMAP 服务已由企业管理员开启。
* POP3 能力较弱，通常不适合作为管理系统的长期同步协议，可作为未来兼容项而非首选。
* Microsoft 365 / Gmail 在企业环境往往需要 OAuth 2.0；普通企业邮箱可能使用应用专用密码。认证方式会直接影响配置表、刷新令牌与部署密钥契约。

### DeepSeek integration baseline

* 官方 API 提供 OpenAI 兼容接口；后端可继续沿用项目的 Java `HttpClient` + Jackson 模式，避免为单一调用引入重量级 SDK。
* 页面配置契约为 DeepSeek 启用状态、服务地址、模型与 API Key；API Key 采用 AES-256-GCM 加密落库，接口只返回配置状态。
* 使用结构化 JSON 输出，并在持久化前做 schema 校验、邮件引用归属校验和长度限制，防止模型臆造不存在的邮件引用。

### WeCom integration baseline

* 新增企业微信内部应用客户端与用户映射，部署配置至少包括 CorpId、AgentId、Secret；Secret 只由环境变量注入。
* access token 只在服务端缓存并按有效期刷新；推送记录需持久化接收人、摘要 ID、状态、尝试次数、错误摘要与最后尝试时间，但不记录敏感消息全文。
* 必须校验摘要所有者与企业微信 userId 映射一致，避免把个人邮件摘要发送给其他员工。
