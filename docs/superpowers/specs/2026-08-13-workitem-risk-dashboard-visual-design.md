# Work Item Risk Dashboard Visual Design

## Goal

Make requirement, task, and defect analysis easier to scan by turning overdue information into a focused business-risk dashboard instead of three ordinary distribution panels.

## Scope

- Rework `frontend/src/components/WorkItemAnalysisPanel.vue`.
- Simplify analysis section declarations in the three work-item views.
- Improve creation/due/overdue presentation in detail rows.
- Keep APIs, statistics semantics, filters, permissions, and read-only behavior unchanged.

## Information Hierarchy

The shared analysis area has three layers:

1. Header: analysis title, context subtitle, and completion rate.
2. Risk dashboard: overdue count, missing-plan count, overdue age composition, top projects, and top owners.
3. Standard distributions: status, project, owner, source, and priority.

Overdue project/owner/age data must not be repeated in the standard distribution grid.

## Risk Dashboard

The risk dashboard is a full-width band, not a nested card. It uses a warm warning accent alongside neutral gray and existing blue/green analysis colors.

- Overdue count is the dominant risk number.
- Missing due date is secondary and visually neutral.
- Age distribution is a seekable horizontal segmented bar using increasingly strong warning tones.
- Project and owner rankings show the top three rows with count badges and compact relative bars.
- Empty overdue state shows a calm success treatment instead of empty chart placeholders.
- The definition remains visible in subdued text: due date before today and status not completed.

## Detail Dates

- Creation time is low-emphasis metadata.
- Due date is the primary date.
- Overdue days use a compact warning pill with stable dimensions.
- Missing due date uses neutral `未设置计划` text.
- Existing original status remains unchanged.

## Responsive Behavior

- Desktop: risk metrics, age composition, and rankings share one horizontal dashboard band.
- Tablet: metrics and age occupy the first row; rankings occupy the second row.
- Mobile: all blocks stack, ranking labels wrap safely, and no horizontal overlap is allowed.
- Standard distribution panels stay two columns on desktop and one on narrow screens.

## Accessibility

- Risk bands and ranking rows retain text labels and counts; color is not the only signal.
- Segments include accessible labels with count and percentage.
- Existing clickable standard distributions keep button semantics.
- Decorative icons are hidden from assistive technology.

## Verification

- Frontend production build passes.
- `frontend/tests/yunxiao-workitems.spec.ts` verifies one risk dashboard per page, no duplicate overdue distribution panels, age labels, and detail overdue pills.
- Screenshots are checked at desktop and mobile widths before deployment.
- Deployed URL is verified on requirements, tasks, and defects.
