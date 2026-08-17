# Yunxiao Work Item Query Contract

## Scope

Yunxiao `Req`, `Task`, and `Bug` records are read-only projections. They MUST NOT be inserted into `requirement`, `task`, or a local defect table. The authoritative projection is `yunxiao_workitem_cache`.

## Storage

Migration: `backend/src/main/resources/db/migration/V25__extend_yunxiao_workitem_cache.sql`.

Required cache fields are `yunxiao_project_id`, `category`, `status`, `normalized_status`, `source_created_at`, `source_updated_at`, `due_date`, `active`, and `last_synced_at`. Project access is resolved by joining `yunxiao_project_id` to every enabled `yunxiao_project_mapping`; the legacy cache `project_id` is not the unified-query ownership key.

`source_created_at` accepts Yunxiao millisecond epochs and ISO timestamps. Cloud due dates prefer custom field `ExpCompletionTime` / `期望完成时间`, then fall back to field `80` / `计划完成时间`, then supported top-level due fields. Migration `V26__add_yunxiao_workitem_dates.sql` backfills both fields from existing `raw_json`.

Status groups are `PENDING`, `IN_PROGRESS`, `COMPLETED`, and `OTHER`. `YunxiaoWorkItemConstants.normalizeStatus` uses cloud status metadata first and display text second. The original status is always retained.

## APIs

### Requirement overview

`GET /api/requirements/overview`

Permission: `requirement:list`.

Parameters: `page`, `size`, `businessLineId`, `projectId`, `assigneeId`, `dataSource`, `normalizedStatus`, `type`, `priority`, `keyword`.

A local requirement linked through `yunxiao_workitem_link` remains one `LOCAL` record and receives `linkedYunxiaoWorkitemId`, `linkedYunxiaoSerialNumber`, `linkedYunxiaoStatus`, and `linkedYunxiaoLastSyncedAt`. Its cloud projection MUST NOT also appear as a standalone record. Unlinked cloud requirements appear as read-only `YUNXIAO` records.

### Task overview

`GET /api/tasks/overview`

Permission: `task:list`.

The existing `summary` and `tasks` response shape remains compatible. Every task adds `recordKey`, `dataSource`, `readOnly`, and `normalizedStatus`. Cloud tasks have no local numeric `id`; clients MUST NOT call local update/detail endpoints for them.

### Defect overview

`GET /api/defects/overview`

Permission: `issue:list`.

Parameters: `page`, `size`, `projectId`, `assigneeId`, `normalizedStatus`, `keyword`. Only active cloud `Bug` records are returned. There is no create, edit, status-update, or delete endpoint.

### Cloud detail

`GET /api/yunxiao/workitems/{id}`

Permission: any of `requirement:list`, `task:list`, or `issue:list`. The response is a typed `WorkItemOverviewItem`; `rawJson` and credentials are never returned.

### Synchronization

- `POST /api/yunxiao/sync`: compatible synchronous sync.
- `POST /api/yunxiao/sync/async`: starts one background sync and immediately returns `RUNNING`; repeated calls while running return current state.
- `GET /api/yunxiao/sync/status`: returns `IDLE`, `RUNNING`, `SUCCESS`, or `FAILED` plus timestamps/results.

If `yunxiao_project_mapping.full_synced_at` is null, search omits `gmtModified` and loads full history. Initial full sync does not call per-item effort APIs: existing cached hours and effort rows are preserved, while newly discovered historical items keep null hours. On success, absent work items in that cloud space are marked `active=0`. Later runs search from the previous successful date minus one day and enrich changed items with estimated/actual effort. Shared cloud spaces are synchronized once.

## Analysis Contract

Requirement and defect overview responses include `analysis`; task overview adds the same field alongside the existing `summary/tasks` shape. `analysis` is calculated from the complete permission-filtered and request-filtered item set before detail pagination.

Fields:

- `statusDistribution[]`, `projectDistribution[]`, `ownerDistribution[]`, `sourceDistribution[]`, `priorityDistribution[]`
- `overdueProjectDistribution[]`, `overdueOwnerDistribution[]`, `overdueAgeDistribution[]`
- every distribution row has `key`, `label`, `count`, and `percentage`
- `totalEstimatedHours`, `totalActualHours`, `completionRate`, `unassignedCount`, `overdueIncompleteCount`, and `missingDueDateCount`

`overdueIncomplete` means `dueDate < current business date` and normalized status is not `COMPLETED`. Records without a due date are counted only in `missingDueDateCount`; they are never inferred as overdue. Age buckets are 1-7, 8-30, 31-90, and more than 90 days.

Requirement priority distribution includes local priorities and `未设置` for cloud records without priority. Shared-space items remain one work item, but project distribution counts the item under every visible mapped project; project percentages may therefore sum above 100%.

## Response Identity

- Local record: `recordKey=local:{numericId}`, `dataSource=LOCAL`, `readOnly=false`.
- Cloud record: `recordKey=yunxiao:{workitemId}`, `dataSource=YUNXIAO`, `readOnly=true`.

Shared-space cloud records appear once globally with all visible `projectIds/projectNames`. Project-scoped roles receive only mapped projects returned by `DataPermissionService`.

## Error Matrix

| Case | Result |
|------|--------|
| Integration not configured | sync fails with the existing configuration message; cached list reads remain available |
| Unknown cloud status | record is returned with `normalizedStatus=OTHER` |
| Cloud detail outside project permission | not-found/no-access runtime error; no raw record leak |
| One mapped project has no work items | empty response with zero summary |
| Full sync fails | previous active cache remains visible; mapping records `FAILED` |

## Required Tests

- `YunxiaoIntegrationServiceTest`: full history, incremental overlap, shared-space single scan, source projection, stale reconciliation.
- `YunxiaoWorkItemQueryServiceTest`: category separation, shared-space deduplication, project permission.
- `RequirementOverviewServiceTest`: linked requirement merge and unlinked cloud requirement visibility.
- `TaskServiceTest`: local/cloud merge and cloud read-only identity.
- Full command: `cd backend && mvn test`.
