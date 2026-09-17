# Yunxiao Work Item UI Contract

## Navigation

`frontend/src/layouts/MainLayout.vue` keeps separate workbench links for `/requirements`, `/tasks`, and `/defects`. `/defects` renders `DefectsView.vue`; it is not a generic combined work-item page.

## Source Behavior

Frontend type contract: `frontend/src/types/work-item.ts`.

- `LOCAL`: blue source label; existing local actions remain available.
- `YUNXIAO`: yellow source label; no create, edit, delete, or status-update control.
- A linked requirement shows `LOCAL` plus `已关联 {linkedYunxiaoSerialNumber}` and is rendered only once.
- A standalone Yunxiao requirement uses `recordKey` as UI identity and opens a read-only drawer.
- A Yunxiao task has no local numeric `id`; `TasksView.vue` MUST skip `api.getTask` and `api.updateTask` when `readOnly` is true.

## API Consumption

- Requirements use `api.getRequirementOverview` for list data. Local CRUD continues using the existing requirement endpoints.
- Tasks use the extended `api.getTaskOverview` response.
- Defects use `api.getDefectOverview` with server page/status/project/assignee/keyword parameters.
- Typed cloud detail is available through `api.getYunxiaoWorkItem`; raw JSON is never rendered.

## Analysis-First Layout

Requirements, tasks, and defects use `frontend/src/components/WorkItemAnalysisPanel.vue` before their detail section. The screen order is metric summary, analysis distributions, then filterable detail.

- Requirements show status, project, source, and priority distributions.
- Tasks show status, project, owner, source, completion rate, and estimated/actual effort execution rate.
- Defects show status, project, owner, source, and completion rate.
- All modules show one shared `.risk-dashboard`: overdue-incomplete and missing-due-date totals, a segmented overdue-age bar, and top-three project/owner rankings. These overdue distributions MUST NOT be repeated in the ordinary `.analysis-grid`.
- Detail records show the real creation time, due date, and overdue days. Creation time is subdued metadata, due date is primary, overdue days use `.overdue-pill`, and missing due dates render as `未设置计划` without an overdue marker.
- Distribution buttons drive the existing detail filters. Analysis data must come from backend `analysis`, not from the current detail page.
- No historical trend is shown until daily status snapshots exist.

## Defect Page

`frontend/src/views/DefectsView.vue` displays:

- total, pending, in-progress, and completed summary counts;
- keyword, project, assignee, and normalized-status controls;
- number, title, mapped projects, assignee, original status, source creation time, and due date;
- server pagination and latest cache sync time;
- a read-only detail drawer with description and effort values.

There is no `新建缺陷` button. Failed loads produce an error message and no write fallback.

## Responsive Rules

The summary uses four stable desktop columns, two columns below 900px, and the toolbar collapses without horizontal text overlap. The table remains the inspectable source of truth on desktop; Element Plus handles horizontal table overflow on narrow screens.

## Good / Base / Bad Cases

- Good: local requirement linked to YX-101 renders once with both labels.
- Base: unlinked cloud Req/Task/Bug renders in its corresponding module with original status.
- Bad: rendering a status `<select>` for `readOnly=true`, calling `/api/tasks/undefined`, or opening a local standalone requirement route with a cloud key.

## Required Tests

`frontend/tests/yunxiao-workitems.spec.ts` asserts requirement deduplication/source filtering, cloud-task read-only behavior, and defect list/detail behavior. Regression commands:

```bash
cd frontend
npm run build
npx playwright test tests/yunxiao-workitems.spec.ts tests/tasks-create.spec.ts tests/requirements-detail-data.spec.ts tests/navigation-permissions.spec.ts
```
