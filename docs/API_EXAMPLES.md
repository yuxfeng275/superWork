# API 使用示例

本文档提供常见 API 使用场景的示例。

## 基础配置

### Base URL
```
http://localhost:8080
```

### 认证
所有需要认证的接口需要在请求头中添加 JWT Token：
```
Authorization: Bearer <your_jwt_token>
```

## 1. 用户认证

### 1.1 用户注册

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "zhangsan",
    "password": "password123",
    "realName": "张三",
    "email": "zhangsan@example.com",
    "phone": "13800138000",
    "role": "研发"
  }'
```

响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 2,
    "username": "zhangsan",
    "realName": "张三",
    "email": "zhangsan@example.com",
    "role": "研发",
    "status": "启用"
  },
  "timestamp": "2026-04-03T14:00:00"
}
```

### 1.2 用户登录

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin",
    "password": "admin123"
  }'
```

响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "username": "admin",
      "realName": "系统管理员",
      "role": "BU负责人"
    }
  },
  "timestamp": "2026-04-03T14:00:00"
}
```

## 2. 需求管理完整流程

### 2.1 创建需求

```bash
curl -X POST http://localhost:8080/api/requirements \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "businessLineId": 1,
    "projectId": 1,
    "customerContactId": 1,
    "title": "用户登录功能优化",
    "description": "优化用户登录流程，增加验证码功能",
    "type": "项目需求",
    "priority": "高",
    "createdBy": 1
  }'
```

响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "reqNo": "REQ202604030001",
    "title": "用户登录功能优化",
    "status": "待评估",
    "createdAt": "2026-04-03T14:00:00"
  },
  "timestamp": "2026-04-03T14:00:00"
}
```

### 2.2 提交需求评估

```bash
curl -X POST http://localhost:8080/api/requirement-evaluations \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "requirementId": 1,
    "technicalFeasibility": "技术可行，需要集成第三方验证码服务",
    "estimatedHours": 40,
    "estimatedCost": 8000,
    "workBreakdown": "1. 前端UI设计 8h\n2. 后端接口开发 16h\n3. 测试 8h\n4. 部署 8h",
    "evaluatorId": 1
  }'
```

### 2.3 BU决策（通过）

```bash
curl -X POST http://localhost:8080/api/bu-decisions \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "requirementId": 1,
    "decision": "通过",
    "decisionNotes": "同意开发，优先级高"
  }'
```

### 2.4 创建需求设计

```bash
curl -X POST http://localhost:8080/api/requirement-designs \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "requirementId": 1,
    "prototypeStatus": "进行中",
    "uiStatus": "未开始",
    "techSolutionStatus": "未开始"
  }'
```

### 2.5 更新设计状态（全部完成）

```bash
curl -X PUT http://localhost:8080/api/requirement-designs/1 \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "prototypeStatus": "已完成",
    "uiStatus": "已完成",
    "techSolutionStatus": "已完成"
  }'
```

### 2.6 需求确认

```bash
curl -X POST http://localhost:8080/api/requirement-confirmations \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "requirementId": 1,
    "confirmationType": "客户确认",
    "confirmedBy": 1,
    "confirmationNotes": "客户确认设计方案，同意开发"
  }'
```

### 2.7 创建任务

```bash
curl -X POST http://localhost:8080/api/tasks \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "requirementId": 1,
    "title": "前端UI开发",
    "description": "开发登录页面UI",
    "assigneeId": 2,
    "estimatedHours": 8,
    "priority": "高"
  }'
```

### 2.8 记录工时

```bash
curl -X POST http://localhost:8080/api/work-logs \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "taskId": 1,
    "userId": 2,
    "workDate": "2026-04-03",
    "hours": 4,
    "workContent": "完成登录页面布局"
  }'
```

### 2.9 更新任务状态

```bash
curl -X PUT http://localhost:8080/api/tasks/1 \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "status": "已完成"
  }'
```

### 2.10 需求交付

```bash
curl -X POST http://localhost:8080/api/requirement-deliveries \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "requirementId": 1,
    "deliveredBy": 1,
    "deliveryNotes": "功能已上线，交付给客户验收"
  }'
```

### 2.11 需求验收

```bash
curl -X POST http://localhost:8080/api/requirement-deliveries/1/accept \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "acceptedBy": 1,
    "acceptanceNotes": "客户验收通过"
  }'
```

## 3. 查询和统计

### 3.1 分页查询需求

```bash
curl -X GET "http://localhost:8080/api/requirements?page=1&size=10&status=开发中" \
  -H "Authorization: Bearer <token>"
```

### 3.2 查询需求详情

```bash
curl -X GET http://localhost:8080/api/requirements/1 \
  -H "Authorization: Bearer <token>"
```

### 3.3 查询需求的任务列表

```bash
curl -X GET http://localhost:8080/api/tasks/requirement/1 \
  -H "Authorization: Bearer <token>"
```

### 3.4 查询任务的工时记录

```bash
curl -X GET http://localhost:8080/api/work-logs/task/1 \
  -H "Authorization: Bearer <token>"
```

### 3.5 获取需求统计

```bash
# 全局统计
curl -X GET http://localhost:8080/api/statistics/requirements \
  -H "Authorization: Bearer <token>"

# 按项目统计
curl -X GET "http://localhost:8080/api/statistics/requirements?projectId=1" \
  -H "Authorization: Bearer <token>"

# 按业务线统计
curl -X GET "http://localhost:8080/api/statistics/requirements?businessLineId=1" \
  -H "Authorization: Bearer <token>"
```

响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "totalCount": 100,
    "projectRequirementCount": 80,
    "productRequirementCount": 20,
    "pendingEvaluationCount": 10,
    "evaluatingCount": 5,
    "pendingDesignCount": 8,
    "designingCount": 12,
    "pendingConfirmationCount": 6,
    "developingCount": 25,
    "testingCount": 15,
    "pendingReleaseCount": 5,
    "releasedCount": 8,
    "deliveredCount": 4,
    "acceptedCount": 2,
    "rejectedCount": 0,
    "totalEstimatedHours": 2000.00,
    "totalActualHours": 1500.00
  },
  "timestamp": "2026-04-03T14:00:00"
}
```

### 3.6 获取任务统计

```bash
# 全局统计
curl -X GET http://localhost:8080/api/statistics/tasks \
  -H "Authorization: Bearer <token>"

# 按需求统计
curl -X GET "http://localhost:8080/api/statistics/tasks?requirementId=1" \
  -H "Authorization: Bearer <token>"

# 按负责人统计
curl -X GET "http://localhost:8080/api/statistics/tasks?assigneeId=2" \
  -H "Authorization: Bearer <token>"
```

响应：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "totalCount": 50,
    "pendingCount": 10,
    "inProgressCount": 25,
    "completedCount": 15,
    "highPriorityCount": 20,
    "mediumPriorityCount": 25,
    "lowPriorityCount": 5,
    "totalEstimatedHours": 400.00,
    "totalActualHours": 300.00,
    "hoursCompletionRate": 75.00
  },
  "timestamp": "2026-04-03T14:00:00"
}
```

## 4. 事项管理

### 4.1 创建事项

```bash
curl -X POST http://localhost:8080/api/issues \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "relatedType": "需求",
    "relatedId": 1,
    "title": "登录接口性能问题",
    "description": "登录接口响应时间过长，需要优化",
    "issueType": "问题",
    "severity": "高",
    "assigneeId": 2,
    "createdBy": 1
  }'
```

### 4.2 更新事项状态

```bash
curl -X PUT http://localhost:8080/api/issues/1 \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "status": "已解决",
    "resolution": "优化了数据库查询，响应时间从2s降低到200ms"
  }'
```

### 4.3 查询需求的事项列表

```bash
curl -X GET http://localhost:8080/api/issues/related/需求/1 \
  -H "Authorization: Bearer <token>"
```

## 5. 附件管理

### 5.1 创建附件记录

```bash
curl -X POST http://localhost:8080/api/attachments \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "relatedType": "需求",
    "relatedId": 1,
    "fileName": "需求文档.pdf",
    "filePath": "/uploads/2026/04/03/requirement_doc.pdf",
    "fileSize": 1024000,
    "fileType": "application/pdf",
    "uploadedBy": 1
  }'
```

### 5.2 查询附件列表

```bash
curl -X GET http://localhost:8080/api/attachments/related/需求/1 \
  -H "Authorization: Bearer <token>"
```

## 6. 组织架构管理

### 6.1 创建业务线

```bash
curl -X POST http://localhost:8080/api/business-lines \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "电商业务线",
    "code": "ECOMMERCE",
    "description": "电商相关业务",
    "leaderId": 1
  }'
```

### 6.2 创建项目

```bash
curl -X POST http://localhost:8080/api/projects \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "businessLineId": 1,
    "name": "电商平台",
    "code": "ECOM_PLATFORM",
    "description": "电商平台项目",
    "managerId": 1,
    "startDate": "2026-01-01",
    "endDate": "2026-12-31"
  }'
```

### 6.3 添加项目成员

```bash
curl -X POST http://localhost:8080/api/project-members \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": 1,
    "userId": 2,
    "role": "研发工程师"
  }'
```

### 6.4 查询项目树

```bash
curl -X GET http://localhost:8080/api/projects/tree \
  -H "Authorization: Bearer <token>"
```

## 7. 错误处理

### 错误响应格式

```json
{
  "code": 400,
  "message": "需求不存在",
  "data": null,
  "timestamp": "2026-04-03T14:00:00"
}
```

### 常见错误码

- `200` - 成功
- `400` - 请求参数错误
- `401` - 未认证
- `403` - 无权限
- `404` - 资源不存在
- `500` - 服务器内部错误

## 8. 分页参数

所有分页接口支持以下参数：

- `page` - 页码（从1开始，默认1）
- `size` - 每页数量（默认10）

分页响应格式：
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [...],
    "total": 100,
    "size": 10,
    "current": 1,
    "pages": 10
  },
  "timestamp": "2026-04-03T14:00:00"
}
```

## 9. 筛选参数

### 需求筛选
- `status` - 状态
- `type` - 类型
- `priority` - 优先级
- `projectId` - 项目ID
- `businessLineId` - 业务线ID
- `search` - 搜索关键词（标题、描述）

### 任务筛选
- `requirementId` - 需求ID
- `assigneeId` - 负责人ID
- `status` - 状态
- `priority` - 优先级

### 工时记录筛选
- `taskId` - 任务ID
- `userId` - 用户ID
- `startDate` - 开始日期
- `endDate` - 结束日期

### 事项筛选
- `relatedType` - 关联类型
- `relatedId` - 关联ID
- `issueType` - 事项类型
- `severity` - 严重程度
- `status` - 状态
- `assigneeId` - 负责人ID

## 10. 使用 Postman

可以导入以下 Postman Collection 快速测试 API：

1. 访问 Swagger UI：`http://localhost:8080/swagger-ui.html`
2. 点击右上角的 "Export" 按钮
3. 导入到 Postman

或者手动创建 Collection，参考本文档的示例。

---

**更多信息请参考：**
- Swagger UI：http://localhost:8080/swagger-ui.html
- 项目文档：docs/PROJECT_COMPLETE.md
