# 大事儿参与人及负责人周进度反馈设计

## 目标

将大事儿从固定 `admin/yufeng` 白名单访问扩展为动态事项关系授权：

- `admin/yufeng` 保留全部事项管理和周进度管理能力。
- 当前负责或参与至少一条大事儿的用户可以进入大事儿管理并查看全部事项、详情和周会。
- 普通用户只能对当前由自己负责的大事儿新增、修改或删除周进度。
- 普通参与人只读，不能反馈周进度。
- 未负责且未参与任何事项的普通用户不能访问页面或业务 API。
- 大事儿支持多个参与人；本期不维护投入比例或历史参与版本。

## 角色与权限矩阵

| 能力 | `admin/yufeng` | 当前负责人 | 普通参与人 | 无关系用户 |
| --- | --- | --- | --- | --- |
| 查看菜单、列表、详情、周会 | 是 | 是，查看全部 | 是，查看全部 | 否 |
| 创建大事儿 | 是 | 否 | 否 | 否 |
| 编辑事项、调整负责人和参与人 | 是 | 否 | 否 | 否 |
| 删除事项 | 是 | 否 | 否 | 否 |
| 新增周进度 | 全部事项 | 仅当前负责事项 | 否 | 否 |
| 修改/删除历史周进度 | 全部事项 | 仅当前负责事项 | 否 | 否 |

“负责人”始终按 `bu_key_matter.owner_id` 的当前值判断，不按周报 `createdBy` 判断。负责人从 A 调整为 B 后，A 立即失去该事项周进度写权限，B 立即获得包括历史修正与删除在内的写权限。管理员始终可管理全部周进度。

## 数据模型

### 参与人关联表

新增 Flyway 迁移 `V33__add_key_matter_participants_and_access.sql`：

```sql
CREATE TABLE bu_key_matter_participant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    key_matter_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_key_matter_participant (key_matter_id, user_id),
    INDEX idx_key_matter_participant_user (user_id),
    CONSTRAINT fk_key_matter_participant_matter
        FOREIGN KEY (key_matter_id) REFERENCES bu_key_matter(id) ON DELETE CASCADE,
    CONSTRAINT fk_key_matter_participant_user
        FOREIGN KEY (user_id) REFERENCES user(id)
);
```

迁移必须回填现有数据：

```sql
INSERT IGNORE INTO bu_key_matter_participant (key_matter_id, user_id)
SELECT id, owner_id FROM bu_key_matter;
```

约束：

- 每个事项至少包含负责人这一名参与人。
- 同一用户在同一事项中只能出现一次。
- 参与用户必须存在且 `status = 1`。
- 删除事项时参与关系级联删除。
- 删除仍被事项引用的用户继续受数据库外键约束保护。
- 本期只保存当前参与关系，不保存按周版本或投入比例。

### 实体与视图

新增：

- `BuKeyMatterParticipant` 实体。
- `BuKeyMatterParticipantMapper`。
- `BuKeyMatterParticipantView`：`userId`、`username`、`realName`。
- `BuKeyMatterAccessView`：`canAccess`、`canManageAll`、`canFeedbackOwn`。
- `ForbiddenOperationException`：领域授权失败异常。
- `GlobalExceptionHandler` 对 `ForbiddenOperationException` 返回 HTTP 403 和原始业务原因。

`BuKeyMatterRequest` 增加：

```java
private List<Long> participantIds;
```

`BuKeyMatterView` 增加：

```java
private List<BuKeyMatterParticipantView> participants = new ArrayList<>();
```

兼容规则：

- 创建时 `participantIds` 为 `null`、省略或空数组，按仅负责人处理。
- 更新时 `participantIds` 为 `null` 或省略，保留当前参与人并自动加入本次提交的 `ownerId`。
- 更新时显式空数组表示权威替换，结果为仅负责人。
- 创建或更新时显式非空数组表示权威替换：后端去重并自动加入 `ownerId`。
- 创建与更新必须在同一事务内保存事项和参与关系。

更新请求区分“未提供”和“显式空数组”是旧客户端安全规则。旧版本客户端编辑标题、日期等字段时不会携带 `participantIds`；若将这种更新解释为仅负责人，会静默删除新客户端已经维护的参与关系。因此省略/`null` 更新必须保留现有关系，而显式数组才表示管理员确认替换参与人集合。负责人发生变化时，省略/`null` 更新保留原关系并补入新负责人；需要移除原负责人时必须提交显式数组。

## 权限模型

### RBAC 权限

保留：

- `bu:key-matter:manage`：管理员管理权限。

新增：

- `bu:key-matter:view`：调用访问能力及只读接口的基础权限。
- `bu:key-matter:feedback`：调用周进度写接口的基础权限。

迁移将 view/feedback 分配给当前所有启用角色。权限只允许请求进入领域授权层，不代表用户一定可访问：最终还必须通过当前负责人/参与人关系校验。后续新建自定义角色由管理员在角色管理中分配这两个权限。

### 领域授权

移除 `BuKeyMatterController` 类级 `@RequireUsername({"admin", "yufeng"})`，改用方法级权限与领域校验：

| API | RBAC | 领域校验 |
| --- | --- | --- |
| `GET /api/key-matters/access` | view / feedback / manage 任一 | 返回能力，不因无关系返回 403 |
| `GET /api/key-matters` | view / manage | 管理员或当前参与至少一项 |
| `GET /api/key-matters/{id}` | view / manage | 管理员或当前参与至少一项；通过后可看全部事项 |
| `GET /api/key-matters/meeting` | view / manage | 管理员或当前参与至少一项 |
| `POST /api/key-matters` | manage | 仅 `admin/yufeng` |
| `PUT /api/key-matters/{id}` | manage | 仅 `admin/yufeng` |
| `DELETE /api/key-matters/{id}` | manage | 仅 `admin/yufeng` |
| `PUT .../weekly-updates/{week}` | feedback / manage | 管理员或事项当前负责人 |
| `DELETE .../weekly-updates/{week}` | feedback / manage | 管理员或事项当前负责人 |

管理员身份仍同时要求用户名为 `admin/yufeng` 和 `bu:key-matter:manage`，保持原安全边界。普通负责人/参与人的访问资格通过参与关系计算；负责人因数据库不变量始终也是参与人。

## 后端设计

### 访问能力服务

新增 `BuKeyMatterAccessService`，注入参与关系 Mapper、事项 Mapper 和 `SysRoleService`，提供：

```java
BuKeyMatterAccessView resolveAccess(Long userId, String username);
void requireReadAccess(Long userId, String username);
void requireManageAll(Long userId, String username);
void requireFeedback(BuKeyMatter matter, Long userId, String username);
```

规则：

- 用户名为 `admin/yufeng` 且数据库权限包含 `bu:key-matter:manage`：`canAccess=true`、`canManageAll=true`、`canFeedbackOwn=true`。
- 至少存在一条 `participant.user_id = userId`：`canAccess=true`。
- 至少存在一条 `bu_key_matter.owner_id = userId`：`canFeedbackOwn=true`。
- 仅参与但不负责事项：`canAccess=true`、`canManageAll=false`、`canFeedbackOwn=false`。
- 无参与关系：三个能力均为 false。
- `canFeedbackOwn` 只表示用户当前至少负责一条事项；具体写入仍必须在锁后校验目标事项 `ownerId`。

### 参与关系同步

创建/更新事项时：

1. 锁定或创建事项。
2. 校验负责人和显式提交的所有参与人均为启用用户。
3. 创建时将 `null`/省略/空数组归一为仅负责人；显式非空数组去重并加入负责人。
4. 更新时将 `null`/省略归一为“当前参与人 + 新负责人”；显式数组归一为“请求参与人去重 + 新负责人”。
5. 在同一事务内用归一后的集合替换参与关系。
6. 负责人调整时新负责人始终自动加入；省略/`null` 更新保留原负责人，显式数组只有仍包含原负责人时才保留，因此前端默认保留、管理员可移除。

### 写入竞态

周进度 upsert/delete 继续先通过 `SELECT ... FOR UPDATE` 锁定事项行，然后执行领域授权：

- 锁后 `ownerId = userId`：普通负责人可写。
- 锁后负责人已变化：返回 `403` 和 `仅事项负责人可反馈周进度`。
- `admin/yufeng` 仅在同时具备 `bu:key-matter:manage` 时跳过所有者比较。
- 授权失败抛出 `ForbiddenOperationException`，由全局异常处理器返回 HTTP 403；不得使用普通 `RuntimeException` 导致 400。
- 完成事项限制继续生效：即使是负责人，也不能为已完成事项新增不存在的周进度。

事项更新同样锁定事项行，负责人变更与周进度写入按同一行串行化。

## API 契约

### 访问能力

```http
GET /api/key-matters/access
```

```json
{
  "code": 200,
  "data": {
    "canAccess": true,
    "canManageAll": false,
    "canFeedbackOwn": true
  }
}
```

无关系用户调用 access 返回 `200` 和全部 false；调用列表、详情或周会返回 `403`。

### 创建/更新请求

```json
{
  "title": "会员体验专项",
  "ownerId": 7,
  "participantIds": [7, 16, 21],
  "priority": "P1",
  "status": "推进中",
  "progress": 40,
  "startDate": "2026-08-03",
  "plannedCompletionDate": "2026-09-30",
  "sortOrder": 0
}
```

### 事项响应

```json
{
  "id": 11,
  "ownerId": 7,
  "ownerName": "石家乐",
  "participants": [
    { "userId": 7, "username": "shijiale", "realName": "石家乐" },
    { "userId": 16, "username": "yufeng", "realName": "于峰" }
  ]
}
```

## 前端设计

### 能力状态

`auth` store 增加大事儿能力缓存：

- `keyMatterAccess`
- `loadKeyMatterAccess(force?: boolean)`
- 登出时清理缓存。

`MainLayout` 根据 `canAccess` 显示菜单。路由守卫对 `/key-matters` 和 `/key-matters-meeting` 调用能力接口；无能力返回首页。页面加载时强制刷新一次，确保负责人/参与关系变更尽快生效。

能力接口 `401` 按现有逻辑退出登录；`403` 或其他业务错误按无访问能力处理，但保留错误日志/提示。

### 管理员表单

管理员新增/编辑事项：

- 负责人保持单选。
- 参与人使用可搜索多选控件。
- 负责人变化时自动加入参与人集合。
- 负责人对应标签不可移除；原负责人默认保留，可由管理员移除。
- 请求提交 `participantIds`。

### 展示

- 列表新增参与人展示，显示头像/姓名，超出可见数量折叠为 `+N`。
- 详情展示完整参与人列表，负责人带“负责人”标识。
- 周会和演示视图显示负责人及参与人，但不增加参与人写入口。

### 操作可见性

页面使用能力和当前用户 ID 派生：

```ts
canManageMatter = access.canManageAll
canFeedbackMatter(matter) = access.canManageAll
  || (access.canFeedbackOwn && matter.ownerId === currentUser.id)
```

- 新增、编辑、删除事项只由 `canManageMatter` 控制。
- 周进度新增、历史编辑/删除、周会内联编辑和演示保存由 `canFeedbackMatter` 控制。
- 普通参与人看到全部内容但所有周进度入口只读。
- 后端 `403` 后刷新 access 和事项数据；若访问资格已失效，退出大事儿页面。

## 错误矩阵

| 条件 | 结果 |
| --- | --- |
| 未登录 | HTTP 401 |
| access 查询且无事项关系 | 200，三个能力均 false |
| 无事项关系访问列表/详情/周会 | HTTP 403 |
| 普通负责人访问全部列表/详情/周会 | 200 |
| 普通参与人访问全部列表/详情/周会 | 200 |
| 普通参与人写周进度 | HTTP 403，`仅事项负责人可反馈周进度` |
| 负责人访问 access | 200，`canAccess=true`、`canFeedbackOwn=true` |
| 仅参与用户访问 access | 200，`canAccess=true`、`canFeedbackOwn=false` |
| 负责人写自己事项周进度 | 成功 |
| 负责人写其他事项周进度 | HTTP 403，`仅事项负责人可反馈周进度` |
| 负责人创建/编辑/删除事项 | HTTP 403 |
| 管理员操作任意事项 | 成功 |
| 写入锁定后发现负责人已变化 | HTTP 403，前端刷新为只读 |
| 参与人包含不存在或停用用户 | HTTP 400，`参与人不存在或已停用` |
| `participantIds` 缺少负责人 | 后端自动补入 |
| 创建 `participantIds` 为 null/省略/空数组 | 仅保存负责人 |
| 更新 `participantIds` 为 null/省略 | 保留当前参与人并加入新负责人，避免旧客户端静默删关系 |
| 更新 `participantIds` 为显式空数组 | 用仅负责人替换当前参与关系 |
| 更新 `participantIds` 为显式非空数组 | 用去重后的请求集合加负责人替换当前参与关系 |
| 已完成事项新增周进度 | 保持现有 `已完成事项无需新增周进展` 规则 |

## 测试与验收

### 数据库与服务

- V33 迁移创建表、唯一约束、索引、外键并回填所有现有负责人。
- 创建事项对 null/省略/空数组保存仅负责人；显式列表自动去重并加入负责人。
- 更新事项对 null/省略保留当前参与人并加入新负责人；显式空数组替换为仅负责人；显式列表替换、去重并加入负责人。
- 不存在/停用参与人被拒绝。
- access 对管理员、负责人、仅参与用户、无关系用户返回正确且不同的能力组合。
- 领域授权异常由全局异常处理器稳定返回 HTTP 403 和原始原因。
- 普通负责人只能写当前负责事项。
- 负责人变更与周进度并发时，锁后按新负责人拒绝旧负责人。
- 完成事项限制与参与权限组合后仍正确。

### 控制器与权限

- `admin/yufeng + manage` 可管理全部。
- 负责人/参与人具备基础权限时可读全部。
- 无关系用户具备基础权限仍被领域层拒绝。
- 普通负责人无法调用事项 CRUD。
- 普通参与人无法调用周进度写接口。
- access 接口对无关系用户返回 false 而非 403。

### 前端 E2E

- 无关系用户看不到菜单，直接路由返回首页。
- 负责人登录后看到菜单和全部事项，只能更新本人事项。
- 参与人登录后看到菜单和全部事项，但没有周进度写入口。
- 管理员表单可维护参与人，负责人自动加入且不可移除。
- 列表折叠展示参与人，详情完整展示并标记负责人。
- 周会和演示模式只允许负责人编辑本人事项。
- 负责人变更后旧负责人保存收到 403，页面刷新为只读。
- 已完成事项停止周度更新的现有 4 条生产回归继续通过。

## 发布与回滚

- 先执行数据库备份，再发布 V33 迁移和后端。
- 后端健康并完成 access/授权 API 冒烟后发布前端。
- 生产冒烟使用管理员、负责人、普通参与人、无关系用户四类账号。
- 验证负责人回填数量等于现有大事儿数量。
- 回滚应用版本时保留参与关系表无副作用；如需完全回滚，先备份后删除新增权限关联和参与关系表。
