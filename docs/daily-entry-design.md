# 日常工作入口增强（路线 B）设计

> 决策：企微降级为待观察；先做零依赖的 Web 入口深化——通知 + 深链 + 移动可用性。

## 1. 站内通知中心（核心）

### 数据模型（V53 迁移）
```sql
CREATE TABLE ai_notice (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  kind VARCHAR(32) NOT NULL,        -- WORKLOG_MISSING | OA_PENDING | SYSTEM
  title VARCHAR(200) NOT NULL,
  body VARCHAR(1000) NULL,
  link VARCHAR(300) NULL,           -- 前端深链，如 /ai-assistant
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  read_at DATETIME NULL,
  KEY idx_user_unread (user_id, read_at)
)
```
不落外部系统数据，只存"提醒事实"——外部数据实时拉。

### 生成时机（拉模式，避免回调依赖）
- **登录时 + 每次打开通知面板时实时计算**（不落库），落库仅用于已读状态：
  - 工时缺填：`WorklogComplianceService` 已有判定逻辑 → "昨日工时未填"
  - OA 待办：`SeeyonOaIntegrationService.listPendingAffairs()` 按身份映射过滤当前用户 → "你有 N 条 OA 待办"
  - 邮箱未绑定：`email_account` 不存在 → 提示绑定
  - 企微映射未配置（未来用）
- **API**：`GET /api/ai/notices`（聚合实时数据 + read 状态），`POST /api/ai/notices/read`（标记已读，按 kind+date 幂等）
- 已读状态存 `ai_notice`（user_id + kind + date 唯一）

### 前端
- 主布局顶栏铃铛（未读数红点），下拉面板：条目 = 图标 + 文案 + 「去处理」深链
- 深链：OA 待办 → `/ai-assistant?prefill=我的OA待办`（AI 助手自动填充提问）；工时 → 工时页；邮箱 → 邮箱页

## 2. AI 助手预填充与深链
- `AiAssistantView` 读取 `route.query.prefill` → 填充输入框并聚焦
- 通知中心、欢迎页能力卡统一走这个机制

## 3. 移动端可用性（轻量）
- 现有响应式已部分覆盖（720px 断点）；本轮只修 AI 助手页移动端硬伤（侧栏隐藏后入口丢失 → 加浮动新建按钮），全站移动优化单独立项不做

## 4. 不做
- 浏览器 Web Notification（需 HTTPS + 权限授权，收益低）
- 邮件/短信通道
- 企微（挂起，等用户量）

## 工作量
- V53 迁移 + 后端 notices API：1 人日
- 前端铃铛 + 面板 + 深链 + AI 预填充：1 人日
- 测试 + 部署：0.5 人日
