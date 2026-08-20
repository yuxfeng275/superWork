# Key Matter Management UI

## 1. Scope and Runtime Access

Routes `/key-matters` and `/key-matters-meeting` use runtime domain
capabilities. They must not use a fixed username or static role allowlist.
Backend authorization remains authoritative.

The API contract is:

```ts
interface KeyMatterAccess {
  canAccess: boolean
  canManageAll: boolean
  canFeedbackOwn: boolean
}

GET /api/key-matters/access
```

`api.getKeyMatterAccess()` receives `unknown` and validates all three fields at
runtime. Missing data, `null`, a non-object, or any non-boolean field is
normalized to three false booleans. Truthy strings such as `"false"` must never
grant access.

The auth store owns `keyMatterAccess` and
`loadKeyMatterAccess(force?: boolean): Promise<KeyMatterAccess>`:

- a resolved value is cached unless `force` is true;
- one in-flight promise is shared even when a concurrent caller requests a
  force refresh (single-flight);
- access errors resolve to a denied capability object;
- login, registration, logout, and token/session replacement reset access;
- every reset increments a generation; a late success/error from an older
  generation cannot repopulate the new session's cache;
- logout clears tokens/user/access before navigation.

`MainLayout` loads the capability and displays the menu only when
`keyMatterAccess?.canAccess === true`. Both routes declare
`requiresKeyMatterAccess`; the async router guard forces a fresh capability
request and redirects denied or malformed responses to `/`. The standalone
meeting route has no application shell.

## 2. Capability-Derived Actions

The page derives actions from runtime access plus the current authenticated
user ID:

```ts
canManageAll = access.canManageAll === true
canFeedbackMatter(matter) = canManageAll || (
  access.canFeedbackOwn === true
  && matter.ownerId === currentUser.id
)
```

`canFeedbackOwn` is only an aggregate hint that the user owns at least one
matter. Every matter-level surface must also compare `matter.ownerId`; the
backend repeats that check after locking.

- `canManageAll` controls matter create, edit, delete, owner selection, and
  participant maintenance.
- `canFeedbackMatter` controls every weekly surface: register update action,
  detail create action, detail history edit/delete, meeting row update action,
  missing-report inline form, presentation edit, and presentation save.
- Direct action functions repeat the capability check even when their button is
  hidden, preventing stale DOM/programmatic invocation from opening or saving.
- A manager can provide feedback for every matter.
- A normal owner can provide feedback only for matters whose current `ownerId`
  equals the logged-in user ID.
- A participant/non-owner can read all returned matters but sees no weekly write
  controls.

## 3. Matter Form and Participants

Matter creation/editing uses a drawer and requires title, owner, start date,
and planned completion date. Manager-only participant behavior is:

- owner remains a searchable single select;
- participants use a searchable multi-select of enabled users;
- selecting/changing owner automatically inserts that owner into participants;
- the current owner cannot be removed from the participant model;
- changing owner preserves the former owner by default, while an administrator
  may remove that former owner explicitly;
- save sends deduplicated `participantIds` including the current owner.

The backend stale-client compatibility contract still applies if another
client omits the field: create omitted/null/empty is owner-only; update omitted
or null preserves current participants and adds the submitted owner; update
explicit empty replaces with owner-only; an explicit list replaces,
deduplicates, and adds owner.

List rows display participant names with owner markers and collapse after the
visible limit as `+N`. The detail drawer displays the complete participant list
and marks the current owner. Meeting rows and presentation metadata display
owner and compact participant context but add no participant write entry.
Frontend participant rendering deduplicates by `userId` and synthesizes the
owner when an older response omits that owner from `participants`.

## 4. Register and Detail Contract

- Register mode shows compact summary counts beside the selected month title,
  filters, the key-matter table, and current-week completion state. Summary
  counts represent the complete register and do not change with keyword,
  status, priority, project, owner, or quick-list filters.
- Register pagination is client-side over the fully filtered response. It shows
  the filtered total, defaults to 10 rows, and supports 10/20/50 rows per page.
  Explicit query, reset, quick-list filter, and page-size changes return to page
  1. Refresh/deletion retains the current page when valid and clamps to the last
  available page when the result becomes shorter.
- Detail uses an overview-first hierarchy: title/status, overall progress with
  week-over-week delta, latest weekly brief, delivery facts, participants, and
  weekly history.
- The weekly brief separates progress, risk, decision/support needs, and next
  action. Weekly history is a timeline and displays each recorded delta.
- Missing current-week content shows `本周待更新` only when the current user may
  provide feedback. A participant/non-owner sees exact text `待负责人反馈`
  instead, with a muted read-only treatment and no write action.
- Explicitly configured female owners (`丛宁`, `姜涛`, `小刘洋`, `黄金玲`, and
  `李芳晨`) use the shared warm owner treatment in register labels/filters and
  meeting avatars. Project grouping and other owners retain existing palettes.

## 5. Weekly, Meeting, and Presentation Contract

The standalone weekly drawer and meeting presentation editor share the same
semantic hierarchy and payload: week/status/progress, this week's outcomes,
problems/risks, coordination/decision needs, and next action. The drawer uses
the full layout; presentation uses a compact embedded layout.

Presentation progress uses the Element Plus slider so pointer position maps to
0-100. Reaching 100 selects `已完成`; moving below 100 from that state selects
`推进中`. Both surfaces retain required outcome validation and call the same
weekly-update API.

Meeting mode:

- uses a selected Monday and displays active matters plus matters completed in
  that Monday-to-Sunday range;
- groups by owner or project; unlinked matters use `BU 内部事项`;
- preserves backend priority/risk order and group matter-count/average-progress
  totals;
- presents progress as the primary narrative, risk and decision needs as a
  signal rail, and next action as a separate execution strip;
- compares the selected week's progress with the exact previous Monday's row;
  missing history is labeled rather than replaced by latest matter progress;
- opens `/key-matters-meeting` in a new tab, leaving the register URL unchanged;
- retains group navigation, compact numbered thumbnails, and previous/next
  controls but no redundant top progress timeline or return-to-register action;
- keeps drafts local to the page and saves through the existing weekly API;
- uses `F` to toggle browser fullscreen and `Escape` to exit fullscreen without
  route navigation.

For a missing report that the current user owns, meeting/presentation shows the
inline editor and save action. For a participant or non-owner, all corresponding
surfaces show exact `待负责人反馈`: register state, meeting comparison/header,
meeting read-only body, group navigation marker, thumbnail state, and
presentation body/actions. These states use a non-pending `waiting` treatment,
contain no edit icon, and still permit detail viewing.

## 6. Milestone, Locale, and Completed Rules

Milestone mode uses each planned completion date as a delivery node, groups by
date inside a selected month, and opens the existing detail drawer. It starts
collapsed; the compact header retains node count and toggles expansion.

Element Plus uses `zh-cn` globally. Date-picker labels are Chinese while API
values remain `YYYY-MM-DD` (`YYYY-MM` for month state).

Completed-matter behavior is preserved across capability classes:

- completed matters suppress create/update-weekly actions in register, detail,
  meeting, and presentation and show `无需更新` rather than `本周待更新`;
- completed matters are included in the register total but excluded from the
  update-meter denominator and missing count;
- detail hides weekly creation but an authorized manager/current owner may
  still edit/delete an already-recorded weekly history row;
- a matter completed during the selected week without a report shows exact
  `本周已完成，无需更新`, no inline editor, no `保存并下一项`, and no pending
  quick-nav/group marker;
- a completed matter with a selected-week report stays read-only in meeting and
  displays that report;
- if the server returns `已完成事项无需新增周进展` after a race, the weekly drawer
  closes and refreshes, or presentation reloads and re-anchors by matter ID;
  the exact message remains visible and stale pending navigation disappears.

## 7. Forbidden Recovery and Route Safety

All HTTP 403 responses use the typed `ApiRequestError.status` check. Recovery
has two paths:

1. Specialized owner-change denial: exact
   `仅事项负责人可反馈周进度` closes the weekly drawer or presentation editor,
   discards that matter's presentation draft, force-refreshes access, refreshes
   matter/meeting data, and becomes read-only while preserving page access. If
   access is gone, it redirects to `/`.
2. Generic 403: CRUD, list, detail, meeting, milestone, and weekly handlers share
   one single-flight forced access refresh. The first active response displays
   the server message. When `canAccess` is false, route changes to `/`; when it
   remains true, current mode/data refreshes so revoked manage/feedback actions
   disappear.

Recovery must be route-safe:

- it only mutates route/UI while the component is mounted and current route is
  `/key-matters` or `/key-matters-meeting`;
- a delayed 403 after the user navigates elsewhere must not redirect, toast, or
  rewrite the new route;
- concurrent duplicate 403s share recovery and do not duplicate the denial
  toast;
- component teardown marks the page inactive and removes global listeners;
- leaving and later re-entering creates a fresh active page instance, so a new
  403 still refreshes capability and exits correctly;
- force refresh in the route guard plus page request recovery ensures relation
  removal is observed on re-entry.

HTTP 401 remains the API client's session-expiry path: clear local credentials
and replace the location with `/login`.

## 8. Loading and Responsive States

- Loading: register/meeting data uses Element Plus loading state. Standalone
  meeting keeps presentation/list DOM unmounted behind a full-viewport loader
  until base data, meeting data, and initial presentation draft are ready.
- Empty: register and meeting modes use contextual empty text.
- Error: load errors persist in an alert; save errors use messages.
- Mobile: summary and filters become two columns; pagination stays in its table
  panel; weekly/briefing content becomes one column; detail facts precede the
  weekly brief; milestone cards stack; wide tables scroll locally.
- Meeting presentation scales by width and height. Desktop navigation rail and
  card share an explicit responsive height; only the group-list remainder
  scrolls. Short/mobile layouts use page scrolling and preserve field/helper
  heights rather than clipping or compressing the form.
- At exactly 1024x768, multi-participant presentation metadata must fit its
  container (`scrollWidth <= clientWidth + 1`) and must not reduce or clip the
  progress region, slider, or progress text; each remains inside the
  presentation-card bounds.
- At 1280x720 and shorter heights the card body has no internal overflow, normal
  form sizing is preserved, and page scrolling reaches the form footer. At
  390x844 there is no page-level horizontal overflow.

## 9. Executable E2E Test Points

`frontend/tests/key-matter-participant-access.spec.ts` must assert:

- manager, owner, and participant capabilities show the menu and permit both
  register and standalone meeting routes; unrelated access hides the menu and
  redirects both direct routes to `/`;
- malformed string and null capability payloads deny access without uncaught
  navigation errors;
- access request counts demonstrate route force-refresh and store single-flight;
- logout/session generation ignores a late allowed response and the next
  session receives a fresh denied response;
- manager form maintains searchable participants, auto-adds the new owner,
  preserves the old owner by default, and sends a deduplicated participant ID
  array;
- owner sees no matter CRUD, can update own register/detail/history/meeting/
  presentation surfaces, and sees another owner's missing matter as
  `待负责人反馈` with detail-only actions;
- participant sees all fixture matters and participant displays but no weekly or
  matter write controls; all missing weekly meeting/presentation markers are
  waiting/read-only rather than pending/editable;
- owner-change 403 keeps the exact server message, closes the editor, refreshes
  access/data, updates owner display, and removes all stale write controls;
- relation-removal 403 redirects once, hides the menu, and suppresses duplicate
  delayed errors;
- a late 403 after navigation leaves the current non-key-matter route unchanged;
  after leaving and re-entering, a fresh 403 still refreshes and redirects;
- manager CRUD 403 refreshes to an accessible read-only page;
- 1024 presentation participant metadata fits and all progress-control geometry
  remains inside the card.

Existing `frontend/tests/key-matters.spec.ts` points remain required: register
pagination/filtering, semantic parity of weekly editors, detail regions,
meeting grouping/comparison/presentation navigation, standalone initialization,
milestone behavior, Chinese dates/ISO payloads, and all completed-matter/race
regressions.

`frontend/tests/navigation-permissions.spec.ts` keeps other static role-route
assertions and mocks an unrelated user's capability as three false booleans;
that user has no key-matter menu and direct `/key-matters` navigation returns to
`/`.

`frontend/tests/responsive-list-layouts.spec.ts` supplies valid manager access
for key-matter routes and asserts the compact 72px sidebar through 1024px,
260px at 1280px, local table overflow, and no main-content overflow.

## 10. Wrong vs Correct

Wrong: derive key-matter access from username/role, trust unvalidated JSON,
show `本周待更新` to a user who cannot act, guard only register buttons, or let a
late 403 redirect after the user has left the page.

Correct: validate and generation-scope capability data, use dynamic menu and
route guards, apply the owner predicate to every weekly surface and direct
action, render `待负责人反馈` for read-only users, and make forced 403 recovery
single-flight, route-aware, and repeatable after re-entry.
