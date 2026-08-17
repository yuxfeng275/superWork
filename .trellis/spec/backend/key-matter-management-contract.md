# Key Matter Management Contract

## 1. Scope / Trigger

This contract applies to BU-level key matters, their weekly updates, and the
meeting query. It does not extend the requirement/task `issue` model.

Only authenticated users whose username is `admin` or `yufeng` may call these
APIs. Both `@RequirePermission("bu:key-matter:manage")` and
`@RequireUsername({"admin", "yufeng"})` are required on the controller.

## 2. Signatures

| Method | Path | Result data |
| --- | --- | --- |
| `GET` | `/api/key-matters` | `BuKeyMatterView[]` |
| `GET` | `/api/key-matters/{id}` | `BuKeyMatterView` with all weekly updates |
| `GET` | `/api/key-matters/meeting?weekStartDate=YYYY-MM-DD` | meeting-ordered `BuKeyMatterView[]` |
| `POST` | `/api/key-matters` | created `BuKeyMatter` |
| `PUT` | `/api/key-matters/{id}` | updated `BuKeyMatter` |
| `DELETE` | `/api/key-matters/{id}` | empty success result |
| `PUT` | `/api/key-matters/{id}/weekly-updates/{weekStartDate}` | upserted weekly update |
| `DELETE` | `/api/key-matters/{id}/weekly-updates/{weekStartDate}` | empty success result |

Database tables are `bu_key_matter` and `bu_key_matter_weekly_update`. The
weekly table has a unique key on `(key_matter_id, week_start_date)` and cascades
when its matter is deleted.

## 3. Contracts

`BuKeyMatterRequest` requires `title`, an active local `ownerId`, `startDate`,
and `plannedCompletionDate`. `projectId` is nullable. Priority is one of
`P0/P1/P2`; status is one of `未开始/推进中/有风险/已阻塞/已完成/已暂停`;
progress is an integer from 0 through 100.

`BuKeyMatterWeeklyUpdateRequest` requires status, progress, and a nonblank
`progressSummary`. The path date must be a Monday. A second write for the same
matter and week updates the existing row.

Saving the latest chronological weekly update synchronizes the matter status,
progress, and completion time. Editing an older week does not roll back the
current matter. Deleting a weekly update never rewrites the current matter.

The meeting query includes every non-completed matter plus matters completed
during the selected Monday-to-Sunday range. Ordering is priority, blocked/risk
state, open before completed, manual sort order, planned completion date, ID.

## 4. Validation & Error Matrix

| Condition | Behavior |
| --- | --- |
| No valid authentication | HTTP `401` |
| Authenticated username outside allowlist | HTTP `403` |
| Missing `bu:key-matter:manage` | HTTP `403` |
| Matter does not exist | resource-not-found response |
| Owner missing or disabled | validation error `负责人不存在或已停用` |
| Planned date before start | validation error `计划完成日期不能早于开始日期` |
| Non-completed status with 100% | validation error |
| Completed status below 100% | server normalizes progress to 100% |
| Weekly path date is not Monday | validation error `周进展日期必须为周一` |

## 5. Good / Base / Bad Cases

- Good: `yufeng` saves the latest week; current status and progress advance.
- Base: `admin` edits a historical week; only that weekly row changes.
- Bad: another business owner calls the endpoint directly; backend returns 403.
- Bad: a non-completed weekly update submits 100%; validation rejects it.

## 6. Tests Required

- `BuKeyMatterServiceTest`: creation defaults, date validation, completion
  transitions, same-week upsert, older-week isolation, meeting inclusion/order.
- `PermissionInterceptorUsernameTest`: both allowed users, disallowed username,
  and missing JWT username attribute.
- `ControllerSecurityContractTest`: permission annotation and username allowlist.
- `frontend/tests/key-matters.spec.ts`: register, detail, weekly update, create,
  and meeting-mode flows.
- `frontend/tests/navigation-permissions.spec.ts`: hidden menu and direct-route
  redirect for users outside the username allowlist.

## 7. Wrong vs Correct

Wrong: rely only on a hidden frontend menu or on `BUSINESS_OWNER`, which would
also allow another business owner to call the API.

Correct: enforce the same username allowlist in the route/menu for usability
and in `PermissionInterceptor` for the security boundary, while also checking
the database permission.
