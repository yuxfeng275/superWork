# Yunxiao BU Dashboard Contract

This contract defines the boundary between local planning, Yunxiao execution
data, scheduled compliance checks, and the BU dashboard.

## Ownership Boundary

- The local system owns BU directions, milestones, project associations,
  pre-development requirements, and management analysis.
- Yunxiao owns R&D work items, assignees, estimated effort, actual effort, and
  execution status.
- Synced Yunxiao data is read-only in this system. The only outbound write is
  requirement handoff when a local requirement enters `开发中`.
- A Yunxiao failure must not roll back the local requirement confirmation.

## Runtime Configuration

`yunxiao_integration_config` is the primary runtime configuration source.
`PUT /api/yunxiao/config` writes one singleton row. The PAT is encrypted with
AES-256-GCM using `YUNXIAO_CONFIG_ENCRYPTION_KEY`; responses expose only
`tokenConfigured` and `tokenSource`.

Environment variables remain a backward-compatible fallback:

| Variable | Required | Meaning |
| --- | --- | --- |
| `YUNXIAO_CONFIG_ENCRYPTION_KEY` | page PAT storage | Base64-encoded 32-byte server key |
| `YUNXIAO_ENABLED` | fallback | Enables network calls when `true` |
| `YUNXIAO_EDITION` | fallback | `center` or `region` |
| `YUNXIAO_BASE_URL` | fallback | OpenAPI endpoint |
| `YUNXIAO_ORGANIZATION_ID` | center fallback | Yunxiao organization ID |
| `YUNXIAO_TOKEN` | fallback | PAT; never exposed by API |

`GET /api/yunxiao/status` exposes configuration booleans but never the token.

## Database Contract

- `bu_direction`, `bu_direction_project`, `bu_direction_milestone` store local
  management plans.
- `yunxiao_project_mapping` maps `project.id` to Yunxiao `spaceId` and the
  requirement `workitemTypeId`. Each local project has at most one mapping,
  while multiple local projects may share one Yunxiao execution space.
- `yunxiao_user_mapping` maps `user.id` to Yunxiao user ID.
- `yunxiao_workitem_cache` stores the latest read-only work item projection.
- `yunxiao_effort_record` stores actual effort by owner and work date.
- `yunxiao_estimated_effort` stores estimated effort by owner. Capacity must
  use this owner, not the work item's `assignedTo`.
- `yunxiao_worklog_snapshot` stores scheduled 18:30 warning and 10:00 final
  snapshots. Both schedules run daily and use `yunxiao_workday_calendar` to
  handle holidays and adjusted workdays. Dashboard reads are calculated
  without writing snapshots.
- `yunxiao_workitem_link` and `yunxiao_handoff_event` implement durable,
  retryable requirement handoff.
- `yunxiao_integration_config` stores page-managed non-secret parameters,
  encrypted PAT, updater, and the latest connection-test result.

## API Contract

### Dashboard

`GET /api/bu-dashboard?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD&planWindowWorkdays=10`

Permission: `bu:dashboard:view`.

Response fields:

- `summary`: direction risk, active requirements, overdue tasks, overloaded
  people, and people with final missing worklogs.
- `directions[]`: local plan, calculated progress, health, projects, and
  milestones.
- `capacity[]`: `actualEffortRate` and `plannedLoadRate` are separate metrics.
- `worklogs[]`: one row per active non-admin user per workday.
- `integration`: safe connection state and mapping counts.

### Direction Management

- `POST /api/bu-directions`
- `PUT /api/bu-directions/{id}`
- `DELETE /api/bu-directions/{id}`

Write permission: `bu:direction:manage`.

Payload fields are `code`, `name`, `objective`, `ownerId`, `startDate`,
`endDate`, `status`, `sortOrder`, `projectIds[]`, and `milestones[]`.

### Yunxiao Management

- `PUT /api/yunxiao/config`
- `POST /api/yunxiao/connection-test`
- `GET /api/yunxiao/projects`
- `GET /api/yunxiao/members`
- `GET|POST /api/yunxiao/project-mappings`
- `GET|POST /api/yunxiao/user-mappings`
- `POST /api/yunxiao/sync`
- `POST /api/yunxiao/requirements/{requirementId}/retry`
- `POST /api/yunxiao/requirements/{requirementId}/bind`
- `POST /api/yunxiao/worklog-exemptions`

Permission: `yunxiao:manage`.

`PUT /api/yunxiao/config` payload:

```json
{
  "enabled": true,
  "edition": "center",
  "baseUrl": "https://openapi-rdc.aliyuncs.com",
  "organizationId": "organization-id",
  "token": "optional-new-pat"
}
```

- `edition` is exactly `center` or `region`.
- `baseUrl` is an HTTP(S) root URL without user info, query, fragment, or
  additional path.
- A center configuration requires `organizationId` when enabled.
- Enabling requires either a new `token`, an existing encrypted token, or the
  environment fallback token.
- Blank/omitted `token` preserves the current encrypted token.
- The response is `IntegrationStatus`: it contains `tokenConfigured`,
  `tokenSource`, and connection-test metadata, but has no token field.

`POST /api/yunxiao/connection-test` uses the saved base URL and PAT against
`GET /oapi/v1/platform/user`. It does not require the integration to be
enabled. Its response fields are `success`, `userId`, `userName`, `email`,
`message`, and `testedAt`.

`GET /api/yunxiao/projects` proxies Yunxiao `SearchProjects`, follows all
pages at 200 records per request, and returns only `id`, `name`, `customCode`,
and `status`. It requires saved connection parameters but does not require the
integration to be enabled. Project mappings store the returned stable `id` as
the Yunxiao `spaceId`; users must not type or copy this ID manually.

`GET /api/yunxiao/members` proxies Yunxiao `SearchMembers`, follows all pages
at 100 records per request, and requests enabled organization members. It
returns `userId`, `memberId`, `name`, `email`, and `status`. User mappings must
store the returned `userId`, because Yunxiao work-item APIs use that value for
assignees; the organization-member `id` is exposed only as `memberId` for
diagnostics. The endpoint uses saved connection parameters and does not require
the integration to be enabled.

Configuration cases:

| Case | Result |
| --- | --- |
| Good: center + base URL + organization + encrypted PAT + enabled | Saved; `configured=true`; sync allowed |
| Base: disabled config with no PAT | Saved; dashboard remains available; no network calls |
| Good: update with blank `token` | Existing ciphertext is retained |
| Bad: enabled center config without organization | HTTP 400; no database write |
| Bad: enabled config without any PAT source | HTTP 400; no database write |
| Bad: malformed/non-root service URL | HTTP 400; no database write |
| Bad: missing server encryption key while a new PAT is supplied | HTTP 400; no plaintext database write |

## Calculation Rules

- Workday default: Monday to Friday, 8 hours. `yunxiao_workday_calendar`
  overrides special dates.
- Users with role `BUSINESS_OWNER` are excluded from worklog compliance rows,
  snapshots, and missing-worklog summary counts. They remain visible in the
  capacity view.
- Same-day warning: 18:30 Asia/Shanghai.
- Historical result: final after the next workday 10:00 snapshot.
- A 429 or 5xx response is retried up to three times with exponential backoff.
- Actual-effort `gmtStart` accepts ISO dates, Unix seconds, or Unix
  milliseconds and is normalized to an `Asia/Shanghai` work date.
- A successful project sync must explicitly clear any previous
  `lastSyncError`; ORM null-update defaults must not leave stale failures.
- Worklog sync searches `Req,Task,Bug` together and limits work-item discovery
  to items modified within the latest 7 days on the first/recovery run. After
  success it resumes from the previous sync date with a one-day overlap. This
  captures newly entered or corrected effort without repeatedly rescanning the
  complete project history.
- Enabled mappings are grouped by Yunxiao `spaceId`; a shared cloud space is
  scanned once and the resulting success/failure state is applied to every
  local mapping in that group.
- Changing a local project's Yunxiao `spaceId` must clear that project's
  work-item, actual-effort, and estimated-effort caches and reset its previous
  sync state before the replacement project is synchronized.
- If any enabled project has not completed a successful sync, mapped users
  receive `数据未知`; stale cache must not become a missing-worklog result.
- Actual effort rate: actual hours in the selected historical period divided
  by expected hours.
- Planned load rate: remaining estimated effort divided by
  `planWindowWorkdays * 8`.
- Load bands: `<60% 可承接`, `60-100% 合理`, `100-120% 饱和`,
  `>120% 超负荷`.
- When cloud estimates are absent, local unfinished task estimates are used
  and `dataCompleteness` states that fallback explicitly.
- When a mapped user has active Yunxiao work items but none of them has owner
  estimates, those cloud items remain visible and planned load is `未知` rather
  than `可承接`.

## Error Matrix

| Case | Local result | Yunxiao result |
| --- | --- | --- |
| Integration disabled | Dashboard works, status is unconfigured | No network call |
| Project/user mapping missing | Confirmation remains `开发中` | Handoff event is `FAILED` and retryable |
| API timeout or non-2xx | Worklog state becomes `数据未知`; cache remains inspectable | Mapping records the last error |
| Existing matching `[REQ-NO]` work item | Link is created | No duplicate work item |
| Invalid direction period | Request returns validation error | No database write |

## Required Verification

- `RequirementConfirmationServiceTest`: confirmation changes local status and
  only enqueues handoff.
- `WorklogComplianceServiceTest`: distinguishes insufficient effort from
  missing mappings, handles holiday overrides, and prevents sync failures from
  becoming missing-worklog results.
- `YunxiaoClientTest`: verifies Region endpoint, PAT request header, and 429
  retry, plus the current-user connection test and paginated project/member
  search.
- `YunxiaoTokenCipherTest` and `YunxiaoConfigServiceTest`: verify authenticated
  encryption, masked storage, and blank-token retention.
- `YunxiaoIntegrationServiceTest`: verifies Unix timestamp effort dates,
  explicit clearing of a previous project-sync error, and removal of derived
  cache data when a mapping changes to another cloud project, plus deduplicated
  synchronization for shared cloud spaces.
- `BuDashboardServiceTest`: verifies that active cloud work without estimates
  remains visible and cannot be reported as available capacity.
- `ControllerSecurityContractTest`: every new controller is protected.
- `frontend/tests/bu-dashboard.spec.ts`: direction, capacity, worklog, config,
  editor, project/member mapping, stable user-ID submission, and overflow
  checks.
## Unreadable Credential Recovery

- A stored encrypted token with a missing/wrong root key must not make dashboard/status/scheduled reads throw.
- Runtime status reports `tokenSource=UNREADABLE`, `configured=false`, `lastTestStatus=CONFIG_ERROR`, and a safe re-entry message.
- Scheduled sync and handoff retry skip while configuration is unreadable. Saving an enabled configuration requires a newly supplied token and a configured root key.

