# Task Overview API

## Scenario: Task management overview

### 1. Scope / Trigger

- Trigger: The task management page needs two fast read views: project -> tasks and person -> projects/tasks.
- Trigger: The same page must filter quickly by task status without N+1 frontend calls.
- Backend source of truth: `task -> requirement -> project`, with `task.assignee_id -> user`.

### 2. Signatures

```http
GET /api/tasks/overview
```

Query params:

| Param | Type | Required | Behavior |
|-------|------|----------|----------|
| `projectId` | number | no | Filters to the project and its child projects. |
| `assigneeId` | number | no | Filters tasks assigned to the user. |
| `status` | string | no | Exact match against `task.status`; supported values include `待开始`, `进行中`, `已完成`, `已测试`. |
| `keyword` | string | no | Case-insensitive contains search across task title/description/type, requirement no/title, project name/path, assignee real name/username. |

Implementation entry points:

```java
TaskController#getTaskOverview(Long projectId, Long assigneeId, String status, String keyword)
TaskService#getTaskOverview(Long projectId, Long assigneeId, String status, String keyword)
```

### 3. Contracts

Response wrapper is the standard `Result<TaskOverviewResponse>`.

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "summary": {
      "totalCount": 5,
      "pendingCount": 2,
      "inProgressCount": 2,
      "completedCount": 1,
      "testedCount": 0,
      "unassignedCount": 1,
      "totalEstimatedHours": 60,
      "totalActualHours": 23,
      "statusCounts": {
        "待开始": 2,
        "进行中": 2,
        "已完成": 1,
        "已测试": 0
      }
    },
    "tasks": [
      {
        "id": 1,
        "requirementId": 101,
        "requirementNo": "REQ-2026-0001",
        "requirementTitle": "PMS系统用户管理模块优化",
        "projectId": 2,
        "projectName": "PMS系统",
        "projectFullPath": "皇家宠物/PMS系统",
        "assigneeId": 3,
        "assigneeName": "李四",
        "assigneeUsername": "tech_li",
        "title": "批量导入接口开发",
        "taskType": "后端开发",
        "estimatedHours": 16,
        "actualHours": 16,
        "status": "已完成"
      }
    ]
  }
}
```

### 4. Validation & Error Matrix

| Case | Expected behavior |
|------|-------------------|
| No params | Return all tasks enriched with requirement/project/user fields. |
| `projectId` has child projects | Include tasks under requirements for the selected project and descendants. |
| `projectId` has no requirements | Return `summary.totalCount = 0` and `tasks = []`. |
| `assigneeId` with no tasks | Return empty summary/list, not 404. |
| Unknown `status` | Return empty summary/list, not 400. |
| Missing requirement/project/user row | Keep the task row and set missing context fields to null. |

### 5. Good/Base/Bad Cases

Base:

```http
GET /api/tasks/overview?status=进行中
```

Good:

```http
GET /api/tasks/overview?assigneeId=3&status=进行中&keyword=租户
```

Bad:

```http
GET /api/tasks?page=1&size=500
GET /api/requirements/{id}
GET /api/projects/{id}
GET /api/users/{id}
```

Do not rebuild the overview with frontend N+1 calls; the API must return enriched rows.

### 6. Tests Required

- Backend compile: `mvn -q -DskipTests compile`.
- Frontend build: `npm run build`.
- Browser smoke with mocked API:
  - Project view renders multiple project groups.
  - Person view groups by assignee and shows project chips.
  - Clicking status `进行中` leaves only rows with `status = 进行中`.
  - Mobile width has `document.documentElement.scrollWidth <= window.innerWidth`.

### 7. Wrong vs Correct

#### Wrong

```typescript
const tasks = await api.getTasks({ size: 500 })
const requirements = await Promise.all(tasks.map(task => api.getRequirementById(task.requirementId)))
```

#### Correct

```typescript
const overview = await api.getTaskOverview({ status: '进行中', assigneeId: 3 })
```
