# AI 连接器通用化 + AI 助手 UI 改版设计（V2）

## 一、目标

1. 连接器从"平铺固定配置项"改为**通用注册模型**：管理员可新建连接系统，凭据形态支持账号密码、Bearer Token、MCP；AI 工具按连接器动态生成。
2. AI 助手 UI 参照主流 agent（Claude/ChatGPT）：居中窄栏对话流、底部统一输入区（输入框+模型选择+工具状态聚合）、欢迎页含能力卡片。

## 二、通用连接器数据模型（V52 迁移）

新表 `ai_connector`，一行 = 一个外部系统连接实例：

```sql
CREATE TABLE ai_connector (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(64) NOT NULL,               -- 唯一编码（slug），工具名前缀
  name VARCHAR(64) NOT NULL,               -- 显示名
  auth_type VARCHAR(16) NOT NULL,          -- BASIC | TOKEN | MCP
  base_url VARCHAR(512) NOT NULL,
  mcp_url VARCHAR(512) NULL,               -- MCP 时使用
  encrypted_username VARCHAR(256) NULL,    -- BASIC
  encrypted_password VARCHAR(512) NULL,    -- BASIC
  encrypted_token VARCHAR(512) NULL,       -- TOKEN / MCP（MCP Bearer）
  enabled TINYINT NOT NULL DEFAULT 0,
  last_test_status VARCHAR(16) NULL,       -- SUCCESS | FAILED
  last_test_message VARCHAR(500) NULL,
  last_tested_at DATETIME NULL,
  built_in TINYINT NOT NULL DEFAULT 0,     -- 内置连接器不可删
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB CHARSET=utf8mb4 COMMENT='AI 连接器注册表';
```

预置数据（V52 INSERT，built_in=1）：worktime（BASIC, https://worktime.lucidata.cn）、yuque（MCP, https://mcp.yuque.com/mcp）。敏感列沿用 `EmailCredentialCipher`（EMAIL_CREDENTIAL_ENCRYPTION_KEY 已在 241 配置）。V51 的 `ai-connector` 配置组废弃（保留行不清理，旧代码不再读）；`ai_connector_identity` 保留复用。

## 三、后端 API（/api/ai/connectors，管理端，system:config:edit）

| 端点 | 说明 |
|---|---|
| GET `/api/ai/connectors` | 列表（脱敏：凭据只回 configured 布尔） |
| POST `/api/ai/connectors` | 新建（code 唯一校验；凭据加密入库） |
| PUT `/api/ai/connectors/{id}` | 更新（凭据留空=不变；改名/URL/启停） |
| DELETE `/api/ai/connectors/{id}` | 删除（built_in 拒绝） |
| POST `/api/ai/connectors/{id}/test` | 连接测试：BASIC→登录探活（通用：POST base_url+/api/auth/login {username,password}，2xx 即成功）；MCP→tools/list |
| GET `/api/ai/connectors/status` | AI 助手面板数据（5 状态口径，合并 built-in 邮箱/云效/OA 状态） |

新组件：
- `entity/AiConnector` + `mapper/AiConnectorMapper`
- `service/AiConnectorRegistryService`（CRUD + 脱敏视图 + 测试分发 + 身份映射解析）
- `controller/AiConnectorController`（管理端 CRUD/测试，@RequirePermission）
- `GenericConnectorClient`（BASIC 登录缓存 token + GET base_url/tool-path 探活查询；MCP 复用 `YuqueMcpClient` 逻辑抽为 `AbstractMcpClient` 参数化 url/token）
- **工具动态生成**：`AiAgentToolService.definitions()` 对 enabled 的通用连接器注入两个通用工具：`query_{code}_search`（GET base_url + queryPath?keyword=&limit=）与 `query_{code}_read`（GET base_url + readPath?id=）。queryPath/readPath 属于连接器扩展字段：V52 加 `query_path VARCHAR(256) NULL`、`read_path VARCHAR(256) NULL`、`result_format VARCHAR(16) DEFAULT 'JSON'`。执行失败统一 isError。

兼容：`ConnectorToolService` 现有 5 内置工具不动；通用工具仅在 `ai_connector.enabled=1` 且测试通过时注册。

## 四、AI 助手 UI 改版（主流 agent 风格）

布局（AiAssistantView.vue 重构）：
- **顶栏**：会话标题 + 右侧「连接器」状态点图标（绿点数量徽标）+ 新建对话按钮。模型选择**移入输入区**。
- **对话区**：`max-width: 820px; margin: 0 auto` 居中窄栏；用户消息右对齐气泡，助手消息无头像左对齐（保留 thinking 折叠块与工具 chip）。
- **欢迎页**：居中大标题 + 4 张能力卡片（查任务/工时、搜邮件、查云效工作项、搜语雀文档），点击卡片填充输入框。
- **输入区（底部固定）**：一行 `+` 按钮（连接器面板 popover，显示各连接器状态点）| textarea 自动增高 | 模型选择（小号）| 发送/停止。参考 Claude：所有操作聚合在输入框周围。
- 会话列表保持左侧栏，项 hover 显示删除；流式渲染逻辑不变。

连接器管理页独立：`frontend/src/views/AiConnectorManageView.vue`（路由 /system/connectors，system:config:edit）——卡片列表 + 新建/编辑对话框（auth_type 切换字段）+ 测试按钮 + 删除。SystemConfigView 中 ai-connector 组的测试区块移除（配置组废弃）。

## 五、实施顺序

1. V52 迁移 + entity/mapper/registry/controller + GenericConnectorClient + 工具注入（后端单测）
2. 管理页前端 + 路由
3. AiAssistantView UI 重构
4. 构建 → 241 部署 → 实测（新建连接器、测试、AI 调用通用工具、面板）

## 六、风险

- 通用 BASIC 探活假设 `/api/auth/login` 契约；不同系统登录端点不同 → query_path/read_path/test_path 可配置，v1 默认值按工时系统。
- 工具名注入需白名单校验 code（`[a-z][a-z0-9_]{1,31}`），防注入。
- 旧 `ai-connector` 配置组与 V51 数据保留但不再读取；面板状态接口向后兼容。
