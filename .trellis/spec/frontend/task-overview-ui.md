# Task Overview UI

## Scenario: Project/person task dashboard

### 1. Scope / Trigger

- Trigger: `frontend/src/views/TasksView.vue` shows operational task status by project and by person.
- Trigger: The page consumes `GET /api/tasks/overview` and must not call requirement/project/user detail APIs per row.

### 2. Signatures

```typescript
api.getTaskOverview(params?: {
  projectId?: number
  assigneeId?: number
  status?: string
  keyword?: string
}): Promise<unknown>
```

UI entry points:

```typescript
loadOverview()
switchView(mode: 'project' | 'person')
setStatus(status: string)
```

### 3. Contracts

- Project view groups rows by `projectId`, using `projectFullPath || projectName || '未归属项目'`.
- Person view groups rows by `assigneeId`, using `assigneeName || assigneeUsername || '未分配'`.
- Status buttons use `summary.statusCounts` and refresh the API with exact `status`.
- Project selector sets `projectId`; person selector sets `assigneeId`.
- Switching views clears the opposite selector to avoid hidden filters.

### 4. Validation & Error Matrix

| Case | Expected behavior |
|------|-------------------|
| API loading | Show Element Plus loading state on the board. |
| Empty result | Show `el-empty` with no stale groups. |
| API error | Show `任务概览加载失败` and clear task rows. |
| Missing project | Group under `未归属项目`. |
| Missing assignee | Group under `未分配`. |
| Mobile width | Sidebar auto-collapses and task page must not create horizontal scroll. |

### 5. Good/Base/Bad Cases

Base:

```typescript
await api.getTaskOverview()
```

Good:

```typescript
await api.getTaskOverview({ assigneeId: 3, status: '进行中', keyword: '租户' })
```

Bad:

```typescript
// Do not keep task rows as local mock data in TasksView.
const tasks = ref([{ title: '数据库设计' }])
```

### 6. Tests Required

- `npm run build` must pass.
- Browser smoke should assert:
  - `.task-group` exists in project view.
  - `按人员` changes grouping text to people/projects.
  - `进行中` status button narrows `.task-row` content.
  - Mobile width `390` has `scrollWidth === innerWidth`.

### 7. Wrong vs Correct

#### Wrong

```typescript
const completedCount = ref(tasks.value.filter(task => task.status === '已完成').length)
```

#### Correct

```typescript
const completedTotal = computed(() => summary.value.completedCount + summary.value.testedCount)
```
