# Key Matter Management UI

## Scope

Route `/key-matters` is visible and navigable only for stored usernames
`admin` and `yufeng`. Backend authorization remains authoritative.

## Page Contract

- Register mode shows compact summary counts beside the selected month title,
  filters, the key-matter table, and current week completion state. Summary
  counts always represent the complete register and do not change with keyword,
  status, priority, project, owner, or quick-list filters.
- Matter creation/editing uses a drawer and requires title, owner, start date,
  and planned completion date.
- Register pagination is client-side over the fully filtered response. It shows
  the filtered total, defaults to 10 rows, and supports 10/20/50 rows per page.
  Explicit query, reset, quick-list filter, and page-size changes return to page
  1. Refresh and deletion retain the current page when valid and clamp it to the
  last available page when the result becomes shorter.
- Detail uses a drawer with an overview-first hierarchy: title and status,
  overall progress with week-over-week delta, latest weekly brief, delivery
  facts, and weekly history. The weekly brief separates progress, risk,
  decision/support needs, and next action instead of rendering them as equal
  text blocks. Weekly history is a timeline and displays each recorded delta.
- Weekly update uses a structured workspace shared by the standalone drawer and
  meeting presentation editor. Both variants present the same hierarchy and
  labels: week/status/progress, this week's outcomes, problems/risks,
  coordination/decision needs, and next action. The drawer uses the full layout;
  presentation mode uses a compact embedded layout. Presentation progress uses
  the Element Plus slider so pointer position maps directly to 0-100. Reaching
  100 selects `已完成`; moving below 100 from that state selects `推进中`.
  They keep the same required outcome validation and existing weekly-update
  payload.
- Meeting mode uses a selected Monday-based week and displays active matters
  plus matters completed that week. Users can group the report by owner or
  project; unlinked matters belong to `BU 内部事项`. Each matter is presented as
  a briefing unit: current progress is the primary narrative, risk and decision
  needs form a signal rail, and the next action is a distinct execution strip.
  Each group keeps matter counts and average-progress totals while preserving
  backend matter priority/risk order.
- Meeting mode opens the standalone `/key-matters-meeting` route in a new browser
  tab, preserving the register page in the original tab. The standalone page
  exposes a presentation view that follows the current meeting ordering. It
  omits the redundant top progress timeline and any return-to-register control,
  while group navigation, compact bottom thumbnails, and keyboard previous/next
  controls remain available. `F` toggles browser fullscreen and `Escape` exits
  browser fullscreen without navigating away. It provides a briefing view for
  updated matters and an inline weekly-update form for missing matters. Saving
  the inline form uses the existing weekly-update API and advances to the next
  matter; drafts remain local to the current page.
- Each meeting matter compares the selected week's progress with the exact
  previous Monday's update. Missing historical data must be labeled instead of
  substituting the matter's latest progress.
- Milestone mode uses each matter's planned completion date as its delivery
  node, groups nodes by date inside a selected month, and opens the existing
  detail drawer from the timeline. The milestone timeline is collapsed by
  default; its compact header keeps the node count visible and toggles the full
  timeline.
- Element Plus uses the `zh-cn` locale globally. Date-picker display labels are
  Chinese while API values remain `YYYY-MM-DD` (or `YYYY-MM` for month state).
- Missing current-week content must display `本周待更新` and remain actionable.
- Explicitly configured female owners (`丛宁`, `姜涛`, `小刘洋`, `黄金玲`, and
  `李芳晨`) use the shared warm owner treatment in register owner labels/filters
  and meeting owner avatars. Project grouping and all other owners retain the
  existing neutral or rotating palette.

## Completed-matter behavior

- Completed matters never surface create/update-weekly actions. Register rows,
  the detail drawer, and meeting group rows suppress the `更新周进展` action and
  show `无需更新` instead of `本周待更新`. Completed matters never count as
  missing.
- Register summary total (`事项概览` all count) includes completed matters, but
  the update meter denominator and missing count (`x/y` and the missing strong
  value) count only non-completed matters, so a completed matter neither inflates
  the denominator nor lowers the update ratio.
- Detail hides the create action for completed matters while the weekly history
  keeps its per-row edit/delete actions so an already-recorded week can still be
  corrected.
- A matter completed during the selected meeting week with no report renders the
  exact `本周已完成，无需更新` state: no inline editor, no `保存并下一项`
  control, and no pending quick-nav/group-row indicator.
- A completed matter that already has a report for the selected week stays
  read-only in meeting and keeps showing that report, never the editor.
- On a completed-write race, where the server returns
  `已完成事项无需新增周进展` for a write that looked valid when rendered: the
  standalone weekly drawer closes and refreshes state, or the presentation
  reloads meeting data and re-anchors to the same matter id. The exact server
  message stays visible, and the refreshed view shows the completed/`无需更新`
  state without stale pending navigation.

## States

- Loading: table/meeting list uses Element Plus loading state. The standalone
  meeting route keeps presentation and list DOM unmounted behind a full-viewport
  bootstrap loader until base data, meeting data, and the first presentation
  draft are ready, preventing stale/list content flashes on first load or refresh.
- Empty: register and meeting modes have contextual empty text.
- Error: API errors appear in a persistent error alert; save errors use messages.
- Mobile: summary becomes two columns, filters become two columns, pagination
  remains inside the table panel, the weekly workspace becomes one column, meeting
  briefing content becomes one column, detail facts move ahead of the weekly
  brief, milestone cards stack, and wide tables scroll inside their panel.
- Meeting presentation scales by both width and height. On regular desktop
  screens, the group-navigation rail and current-matter card share one explicit
  responsive height and align at both edges; compact numbered thumbnails sit
  below the card without affecting that height. The rail keeps its header and
  grouping switch fixed while overflowing group cards scroll only inside the
  remaining rail area; group rows retain their content height instead of being
  compressed. Medium screens reduce navigation
  chrome, narrow screens remove previous/next controls, and regular desktop
  screens keep the complete card visible when it fits. Short desktop screens
  preserve normal field heights and helper text, let the card grow with its
  content, and use one presentation-page scroller instead of compressing or
  clipping the form. Mobile follows the same page-scrolling model.

## Test Points

- `admin` sees the menu and opens `/key-matters`.
- a non-allowlisted user sees no menu and direct navigation redirects to `/`.
- current-week update values prefill the weekly drawer.
- register pagination renders 10 rows by default, switches page and page size,
  resets on explicit and quick filters, and never leaves an invalid empty page.
- standalone and meeting weekly editors expose matching semantic sections and
  preserve status/progress synchronization, prefill, validation, and payload;
  presentation dragging maps 80% of the track to 80 and reaches 100 exactly.
- detail exposes the overall progress, key facts, latest weekly brief, and
  week-over-week timeline through stable semantic regions.
- meeting mode presents progress, problems, next plan, and missing-update state.
- meeting briefing exposes progress, risk, decision, and next-action sections
  through a stable semantic region.
- meeting mode switches between owner and project grouping and shows exact
  selected-week versus previous-week progress deltas.
- meeting mode opens in a new tab, keeps the register URL unchanged, does not
  render a return-to-register control, and does not expose matter content before
  standalone initialization completes.
- meeting presentation mode steps through the ordered matters, renders the
  updated and missing-update states, uses compact numbered thumbnails, does not
  render a top progress timeline, and submits inline updates to the selected
  Monday without introducing a separate API contract.
- meeting presentation fits in one viewport when content and screen height allow;
  at 1280x720 and shorter heights the card body has no internal overflow, normal
  field sizing is preserved, the page reaches the complete form and footer by
  scrolling, and the 390x844 layout has no horizontal overflow.
- milestone mode is collapsed initially and orders planned completion nodes
  inside the selected month after expansion.
- date pickers display Chinese year/month/day labels and still submit ISO dates.
- create request carries owner and date fields exactly as `YYYY-MM-DD`.
- completed matter rows show `无需更新`, never `本周待更新`, and hide the
  `更新周进展` action; the detail drawer hides the same create action while the
  weekly history keeps per-row edit/delete.
- register `事项概览` total includes completed matters while the update meter
  denominator and missing count exclude them.
- a meeting matter completed during the selected week without a report shows the
  exact `本周已完成，无需更新` state with no inline editor, no `保存并下一项`,
  and no pending quick-nav/group-row indicator; a completed matter that already
  has a report stays read-only.
- a completed-write race preserves the server's `已完成事项无需新增周进展`
  message, closes the standalone drawer or reloads the presentation anchored to
  the same matter id, and refreshes to the completed/`无需更新` state without
  stale pending navigation.
