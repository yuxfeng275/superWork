# BU Dashboard UI

`frontend/src/views/StatisticsView.vue` is the management-only BU execution
dashboard at `/statistics`.

## Views

- `方向总览`: direction health, calculated progress, linked projects, and
  milestones. Empty state provides the create action.
- `人员负荷`: current work, overdue tasks, historical actual effort rate,
  future planned load rate, load band, and data completeness.
- `工时检查`: daily actual/expected effort, warning/final state, and role.
  A compact icon-based status control filters rows by completed, missing,
  insufficient, or unresolved state; every status tag repeats the matching
  semantic icon for rapid scanning.
- `云效配置`: page-managed connection parameters, masked PAT state,
  connection test, project mappings, user mappings, and manual sync.

## UI Rules

- The sidebar and route both use `roleAccess: management`.
- Actual effort and planned load must remain separate columns and labels.
- Planned load is for resource planning and must be explicitly labelled as
  not being a performance score.
- `数据未知` must remain visually neutral and must not be presented as
  missing effort.
- `未映射`, `数据未知`, and same-day `待填写` belong to the neutral `待确认`
  filter, not the red `未填写` filter.
- Cloud configuration state must never show the PAT.
- Project mapping uses a filterable Yunxiao project selector populated from
  the saved connection. The selected option stores its stable project ID; an
  existing mapping missing from the latest cloud list remains visible as an
  unavailable fallback option.
- User mapping pairs one local person with a filterable Yunxiao member selected
  by name or email. It stores the member's stable `userId`, prevents duplicate
  active mappings, and keeps an existing user ID visible as an unavailable
  fallback when that member is absent from the latest cloud list.
- Loading, API error, empty direction, disabled sync, and mapping-empty states
  must remain visible and actionable.
- Direction cards and summary cards use at most 8px radius.
- Desktop and 390px mobile widths must not create document-level horizontal
  overflow. Wide tables may scroll inside their own Element Plus container.

## API Consumption

- `api.getBuDashboard({ startDate, endDate, planWindowWorkdays })`
- `api.createBuDirection`, `api.updateBuDirection`, `api.deleteBuDirection`
- `api.getYunxiaoProjectMappings`, `api.saveYunxiaoProjectMapping`
- `api.getYunxiaoProjects`
- `api.getYunxiaoMembers`
- `api.getYunxiaoUserMappings`, `api.saveYunxiaoUserMapping`
- `api.updateYunxiaoConfig`, `api.testYunxiaoConnection`
- `api.syncYunxiao`

All backend responses are unwrapped by `ApiService.request`.

## Regression Test

`frontend/tests/bu-dashboard.spec.ts` verifies:

- all four management views render API data;
- direction editor supports linked projects and milestones;
- page configuration saves and tests the current values without displaying PAT;
- project mapping selects by Yunxiao project name/code and saves its stable ID;
- user mapping selects by Yunxiao member name/email and saves `userId` rather
  than the organization-member ID;
- sync is disabled while configuration is incomplete;
- the document has no horizontal overflow.
