# 连接器收口设计（Connector Hub）

> 目标：把散落在多张表、多个配置组、多个页面里的「外部系统连接配置」统一收口到**连接器**
> 一个概念、一个入口、一份存储。此后新增任何外部系统对接，只在连接器里扩展，不再新增配置页。

## 一、现状：连接配置七处散落

| 外部系统 | 连接参数存储 | 配置入口（页面/接口） | 凭据加密 | 问题 |
|---|---|---|---|---|
| 云效 | `yunxiao_integration_config`（V17，单行 id=1） | BU 驾驶舱 → 云效配置（内嵌在统计页）；`PUT /api/yunxiao/config` | `YunxiaoTokenCipher`（`yunxiao.config-encryption-key`） | 配置藏在报表页里 |
| 工时系统 | `worktime_integration_config`（V56，单行 id=1） | KPI 周报 → 数据同步 → 工时系统集成；`PUT /api/worktime/config` | `WorktimeTokenCipher`（`worktime.*`） | 配置藏在 KPI 报表页里 |
| OA（致远） | `seeyon_oa_integration_config`（V24，单行 id=1） | `PUT /api/seeyon-oa/config`（前端无入口，仅接口） | `SeeyonOaTokenCipher` | 有接口无入口，运维靠 env |
| 语雀 | `system_config_item` 组 `ai-connector`（V51：`yuque.mcp-url/token`）+ 组 `weekly-report`（V59：`yuque.repo/parent-dir/base-url`） | 系统管理 → 配置管理 | `EmailCredentialCipher` | 连接参数与业务参数分散两组 |
| 邮件 | `email_account`（按用户 IMAP/SMTP 凭据）+ 组 `ai-connector`（`mail.search-days`）+ 组 `email-integration`（`app.public-base-url`） | 邮件管理（用户级）+ 配置管理 | `EmailCredentialCipher` | 组织级参数散落 |
| DeepSeek | 组 `email-integration`（`deepseek.*`，摘要）+ 组 `ai-agent`（`aiagent.deepseek.*`，助手） | 配置管理 | `EmailCredentialCipher` | **同一凭据存两份** |
| 智谱 GLM | 组 `ai-agent`（`aiagent.*`） | 配置管理 | `EmailCredentialCipher` | |
| 企业微信 | 组 `email-integration`（`wecom.*`） | 配置管理 | `EmailCredentialCipher` | |
| 通用（AI 工具） | `ai_connector`（V52 注册表，种子 worktime/yuque/oa） | 系统管理 → AI 连接器 | `EmailCredentialCipher` | 注册表与上表**并存**，AI 工具的工时/语雀凭据仍读 V51 组 |
| OA vReport 导出地址 | 组 `oa-vreport`（V76） | 配置管理 | — | 属 OA 连接的延伸参数 |

结论：**同一个外部系统可能在 2-3 处有配置（表 + 配置组 + 注册表），且配置页寄生在报表页里**。

## 二、目标模型

### 2.1 一行 = 一个连接器

唯一存储：`ai_connector`（V52 建立，V79 扩展）。字段语义：

| 字段 | 用途 |
|---|---|
| `code` | 连接器编码（稳定，AI 工具名前缀） |
| `name` | 显示名 |
| `auth_type` | `BASIC` / `TOKEN` / `MCP` / `SEEYON` / `MAIL` |
| `base_url` / `mcp_url` | 服务地址（MCP 时用 mcp_url） |
| `test_path` / `query_path` / `read_path` | 探活路径 / AI 通用查询、读取路径 |
| `encrypted_username/password/token` | 凭据（统一 `EmailCredentialCipher`，AES-256-GCM） |
| `enabled` / `last_test_*` / `built_in` / `sort_order` | 启停、最近探活、内置保护、排序 |
| `extra_config`（V79 新增，JSON） | 系统专属参数（见下表） |

内置连接器（`built_in=1`）：

| code | 名称 | auth_type | 凭据 | extra_config |
|---|---|---|---|---|
| `yunxiao` | 云效 | TOKEN | token | `edition`(center/region)、`organizationId` |
| `worktime` | 工时系统 | BASIC | 工号、密码 | — |
| `oa` | OA（致远） | SEEYON | 账号、密码（可选令牌） | `contractExportUrl`（vReport 销售合同导出地址） |
| `yuque` | 语雀 | MCP | token | `repo`、`parentDir`（周会纪要知识库） |
| `mail` | 邮件 | MAIL | —（按用户绑定） | `searchDays`（`base_url` 存系统外链地址，用于邮件内回链） |
| `deepseek` | DeepSeek | TOKEN | api-key | `model`（助手）、`digestModel`（摘要）、`digestEnabled`（摘要开关） |
| `glm` | 智谱 GLM | TOKEN | api-key | `model` |
| `wecom` | 企业微信 | WECOM | secret | `corpId`、`agentId` |

说明：`auth_type` 取值 `BASIC / TOKEN / MCP / SEEYON / WECOM / MAIL`；
`deepseek` 保留 `digestEnabled` 是为了保持「邮件摘要」原有的独立开关语义
（摘要与助手是两条消费链，不能因为收口而相互启用）。

### 2.2 唯一入口

- 菜单：**系统管理 → 系统配置 → 连接器管理**（`/system/connectors`，页面 `ConnectorManageView`）。
  原「AI 连接器」菜单（`/ai-connectors`）迁移为它；`/ai-connectors` 在两套前端保留重定向。
- 接口：`/api/connectors`（原 `/api/ai/connectors` 全部退役）。
- 页面：卡片列表 = 内置连接器 + 通用连接器；卡片内配置凭据/地址/专属参数、启用、测试；
  卡片底部提供该系统的**延伸动作**（云效映射与同步、工时同步日志、OA 组织与合同同步、邮箱账号、数据集成中心）。
- 其他页面（KPI 周报、驾驶舱）只保留**只读状态 + 去连接器管理**的链接。
- 就绪口径唯一：`READY / DISABLED / NOT_CONFIGURED`（`GET /api/connectors/status`，
  AI 助手面板 `GET /api/ai-agent/connectors` 同源）。

### 2.3 配置组退役

| 配置组 | 处理 |
|---|---|
| `ai-connector`（V51） | 全部键迁移至连接器后 `status=0`（隐藏、不再读取） |
| `email-integration`（V29） | `deepseek.*`→deepseek、`wecom.*`→wecom、`app.public-base-url`→mail；迁完 `status=0` |
| `ai-agent`（V47/V50） | `aiagent.*`（GLM 地址/Key/模型 + DeepSeek 助手档）全部迁入 glm/deepseek 连接器；组长停用不再单独保留，迁完 `status=0` |
| `weekly-report`（V59） | `yuque.*`→语雀连接器；迁完 `status=0` |
| `oa-vreport`（V76） | `sales-contract-export-url`→OA 连接器 `extra_config`；迁完 `status=0` |

## 三、数据搬迁

### 3.1 V79 迁移（结构）

```sql
ALTER TABLE ai_connector ADD COLUMN extra_config TEXT NULL COMMENT '系统专属扩展参数(JSON)';
-- 补齐内置连接器行（yunxiao/mail/deepseek/glm/wecom），worktime/yuque/oa 已存在
```

### 3.2 启动搬迁器（`ConnectorLegacyConfigMigrator`，幂等）

Flyway 无法解密旧密文（三个系统各有独立 cipher/key），因此**值搬迁在 Java 启动时执行**：

```
旧表/旧配置组 ──(旧 cipher 解密)──► 连接器行 ──(EmailCredentialCipher 加密)──► ai_connector
```

规则：
1. 逐 code 处理，**只填空**：连接器行已有值（凭据非空 / extra 已有该键）不覆盖，人工改动永远优先；
   种子默认值（如云效 `https://openapi-rdc.aliyuncs.com`、语雀 MCP 地址）视为空，可被旧值覆盖。
2. 优先级：旧表 > 旧配置组 > 环境变量（保持既有行为）。
3. 搬迁成功后把源配置项 `status=0`（行保留可回溯）；旧表**不删**（保留数据，代码不再读取）。
4. 幂等靠标记：`system_config_item` 隐藏组 `connector-hub` 下每个 code 一个 `<code>.migrated=done`（V79 预置）；
   标记写入后不再执行。搬迁失败（如旧密文解不开）会记录错误并保留标记为空，下次启动重试。
5. 搬迁器异常绝不阻断应用启动（本地/测试库无表时静默跳过）。

## 四、后端改造

| 模块 | 改造 |
|---|---|
| `ConnectorRegistryService`（原 `AiConnectorRegistryService`） | 增加 `extra_config` 读写与校验、`findByCode(code)`、`statuses()`（就绪状态唯一来源）、按 code 的启用前就绪校验 |
| `ConnectorController`（原 `AiConnectorController`） | 路由 `/api/connectors`，新增 `GET /api/connectors/status`，测试走分发器 |
| `ConnectorTestDispatcher`（新增） | 测试分发：`yunxiao`→云效当前用户探活、`worktime`→登录并回报可见业务线、`deepseek`→`/models`、`wecom`→gettoken、`glm`→最小对话请求、`mail`→邮箱账号健康检查，其余按 auth_type（BASIC/TOKEN/MCP/SEEYON） |
| `ConnectorLegacyConfigMigrator`（新增） | 启动时一次性值搬迁（见 §3.2） |
| `YunxiaoConfigService` / `WorktimeConfigService` / `SeeyonOaConfigService` | 变只读适配器：`getRuntimeConfig()` 读对应连接器，env 兜底；`save()`/`recordConnectionTest()` 删除 |
| `EmailIntegrationConfigService` | `deepseek`/`wecom`/`publicBaseUrl` 改读连接器 |
| `AiAgentModelConfigService` | 模型可选列表/解析改读 `glm`、`deepseek` 连接器 |
| `WorktimeClient` | 读工时连接器（不再读 V51 组） |
| `YuqueMcpClient` | 读语雀连接器（`mcp_url`/token） |
| `ConnectorToolService` | `mail.search-days` 改读 mail 连接器 extra |
| `WeeklyReportService` | 语雀 `repo`/`parentDir` 改读语雀连接器 extra |
| `OaContractCollector` | 导出地址改读 OA 连接器 extra |
| `OaNoticeSource`/`WorktimeAnalyticsService` | 不变（继续用 `ai_connector_identity` 身份映射） |

### 端点退役

| 退役 | 替代 |
|---|---|
| `PUT /api/yunxiao/config`、`POST /api/yunxiao/connection-test` | `PUT /api/connectors/{id}`、`POST /api/connectors/{id}/test` |
| `PUT /api/worktime/config`、`POST /api/worktime/connection-test` | 同上 |
| `PUT /api/seeyon-oa/config`、`POST /api/seeyon-oa/connection-test` | 同上 |
| `POST /api/system/configs/{group}/{integration}/test`（deepseek/wecom/worktime/yuque 四个） | 同上 |

保留：`GET /api/yunxiao/status`、`GET /api/worktime/status`、`GET /api/seeyon-oa/status`（只读状态）、
各系统同步/映射接口、`GET /api/ai-agent/connectors`（改由注册表统一产出状态）。

## 五、前端改造

| 前端 | 改造 |
|---|---|
| frontend-pro | `ai-connectors` → 连接器管理（各系统专属字段 + 测试 + 跳转）；KPI 页工时配置弹窗 → 只读状态 + 「去连接器配置」；驾驶舱云效配置 tab → 只读 + 链接；配置管理页移除已收口组与测试按钮 |
| frontend（Vue 旧版） | 同口径：`AiConnectorManageView` 升级 + KPI/驾驶舱/配置管理清理 + 菜单路由更新 |
| 菜单（V79 数据） | `sys_menu` 的「AI连接器」行改为「连接器管理」`/system/connectors`，挂到系统管理分区 |

## 六、风险与回归点

1. **凭据解密失败**：旧密文用旧 key，服务器必须保留 `YUNXIAO/WORKTIME/SEEYON config-encryption-key` 环境变量直到搬迁完成（搬迁器记录 `UNREADABLE` 时跳过并告警，不阻塞启动）。
2. **回归面**：云效同步、工时同步（合同/月度）、OA 组织与合同采集、邮件摘要、AI 助手工具、周报发布——均依赖连接参数，
   搬迁后需逐条验证（见验收清单）。
3. **多 agent 并行**：`ai_connector` 表被 aiagent 分支同时使用，V79 只做**追加**（新增列、新增种子行），不做重命名/删列。
4. **新旧前端并行**：两套前端都指向同一后端接口，改造必须同时落地，否则出现「旧页面调已删接口」。
5. **环境变量兜底的口径差异**：业务执行路径（同步任务、客户端）仍保留「连接器未配置时回落环境变量」的兜底
   （地址与凭据），但**启停开关、就绪状态与 AI 工具裁剪只看连接器**（`READY` 要求连接器内配置完整）。
   由于内置连接器行必然存在（V52/V79 种子），纯环境变量驱动的部署在收口后会表现为「未启用/未配置」——
   这是有意为之：配置应收口到连接器，环境变量只作为过渡兜底。
6. **DeepSeek 一个开关、两条消费链**：连接器 `enabled` 是 provider 级开关（AI 助手与摘要共用），
   每日摘要额外要求 `extra.digestEnabled=true`；因此「助手可用」不会自动开启摘要，
   反向（摘要开启使助手 provider 可用）是可接受的——该 provider 本身已在实际使用。
7. **超时固定为 30s**：原 `yuque/worktime.timeout-seconds` 配置项随收口退役（生产值为默认 30s），
   客户端超时改为常量；如需可配置再以连接器扩展参数形式回归。

## 七、验收清单

- [ ] 连接器管理页可完成：云效/工时/OA/语雀/邮件/DeepSeek/GLM/企微 的配置、启用、测试。
- [ ] 各系统的下游功能（同步、映射、AI 工具、摘要、周报）在配置搬迁后行为不变。
- [ ] 配置管理页不再出现连接类配置组；KPI/驾驶舱不再出现连接配置表单。
- [ ] `GET /api/ai-agent/connectors` 与连接器管理页状态一致。
