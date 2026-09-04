# Key Matter Management Contract

## 1. Scope / Trigger

This contract applies to BU key matters, participant relations, weekly updates,
the meeting query, and the runtime access-capability endpoint. It does not
extend the requirement/task `issue` model.

Access is no longer limited to a fixed username for reads. RBAC permits a
request to enter the domain layer; current participant/owner relations decide
whether a non-manager may read or provide feedback. Both parts are required:
a participant relation plus database `bu:key-matter:view` grants `canAccess`,
and an owner relation plus database `bu:key-matter:feedback` grants
`canFeedbackOwn`. A relationship alone is insufficient. Full management remains
a two-part boundary: username `admin` or `yufeng` **and** database permission
`bu:key-matter:manage`.

## 2. Signatures

| Method | Path | RBAC (OR semantics) | Domain rule | Result data |
| --- | --- | --- | --- | --- |
| `GET` | `/api/key-matters/access` | `view` / `feedback` / `manage` | none; unrelated users receive false capabilities | `BuKeyMatterAccessView` |
| `GET` | `/api/key-matters` | `view` / `manage` | manager or participant in at least one matter | `BuKeyMatterView[]` |
| `GET` | `/api/key-matters/{id}` | `view` / `manage` | manager or participant in at least one matter; relation is domain-wide, not target-only | `BuKeyMatterView` |
| `GET` | `/api/key-matters/meeting?weekStartDate=YYYY-MM-DD` | `view` / `manage` | manager or participant in at least one matter | meeting-ordered `BuKeyMatterView[]` |
| `POST` | `/api/key-matters` | `manage` | username `admin`/`yufeng` with `manage` | created `BuKeyMatter` |
| `PUT` | `/api/key-matters/{id}` | `manage` | username `admin`/`yufeng` with `manage` | updated `BuKeyMatter` |
| `DELETE` | `/api/key-matters/{id}` | `manage` | username `admin`/`yufeng` with `manage` | empty success result |
| `PUT` | `/api/key-matters/{id}/weekly-updates/{weekStartDate}` | `feedback` / `manage` | after locking: current owner, or `admin`/`yufeng` with `manage` | upserted weekly update |
| `DELETE` | `/api/key-matters/{id}/weekly-updates/{weekStartDate}` | `feedback` / `manage` | after locking: current owner, or `admin`/`yufeng` with `manage` | empty success result |

Permission names above abbreviate `bu:key-matter:view`,
`bu:key-matter:feedback`, and `bu:key-matter:manage`.

## 3. Database Contract

The tables are `bu_key_matter`, `bu_key_matter_weekly_update`, and
`bu_key_matter_participant`. The weekly table retains its unique key on
`(key_matter_id, week_start_date)` and cascades when its matter is deleted.

Migration `V33__add_key_matter_participants_and_access.sql` must create:

```sql
CREATE TABLE bu_key_matter_participant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    key_matter_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_key_matter_participant (key_matter_id, user_id),
    INDEX idx_key_matter_participant_user (user_id),
    CONSTRAINT fk_key_matter_participant_matter
        FOREIGN KEY (key_matter_id) REFERENCES bu_key_matter(id) ON DELETE CASCADE,
    CONSTRAINT fk_key_matter_participant_user
        FOREIGN KEY (user_id) REFERENCES user(id)
);
```

It must backfill every existing owner with:

```sql
INSERT IGNORE INTO bu_key_matter_participant (key_matter_id, user_id)
SELECT id, owner_id FROM bu_key_matter;
```

It also creates `view` and `feedback` permissions idempotently and grants them
to all enabled roles. Every matter must retain its current owner as a
participant. Participant users must exist and be enabled; duplicate request
IDs are removed; matter deletion removes participant rows, and the user foreign
key continues to protect referenced users.

## 4. Request and Response Contracts

`BuKeyMatterRequest` requires `title`, an enabled local `ownerId`, `startDate`,
and `plannedCompletionDate`. `projectId` and `participantIds` are nullable.
Priority is `P0/P1/P2`; status is one of
`未开始/推进中/有风险/已阻塞/已完成/已暂停`; progress is an integer from 0
through 100.

Participant synchronization is transactional with the matter write and uses
these exact compatibility rules:

| Operation | `participantIds` input | Stored relation set |
| --- | --- | --- |
| create | omitted/`null` or `[]` | owner only |
| create | explicit non-empty list | deduplicated list plus owner |
| update | omitted/`null` | preserve current participants and add the submitted owner |
| update | explicit `[]` | replace with owner only |
| update | explicit non-empty list | replace with deduplicated list plus owner |

The omitted-update rule is stale-client safety: a client built before
participants existed cannot silently erase participant relations while editing
another matter field. An explicit array is an authoritative replacement.
Changing owner with omitted `participantIds` therefore retains prior
participants and adds the new owner; an administrator must send an explicit
array to remove the old owner.

`BuKeyMatterView.participants` is always a non-null array of:

```json
{
  "userId": 7,
  "username": "shijiale",
  "realName": "石家乐"
}
```

List, detail, and meeting responses include participants for every returned
matter. A caller who has any qualifying relation sees the full domain result,
not only matters in which that caller participates.

`GET /api/key-matters/access` returns primitive booleans:

```json
{
  "canAccess": true,
  "canManageAll": false,
  "canFeedbackOwn": true
}
```

- `canAccess`: manager, or a user with `view` and at least one participant row.
- `canManageAll`: username `admin`/`yufeng` and `manage`.
- `canFeedbackOwn`: manager, or a user with `feedback` who currently owns at
  least one matter. This aggregate capability never replaces the target-owner
  check under lock.
- No relation returns HTTP `200` with all three values false.

## 5. Weekly and Completed-Matter Contracts

`BuKeyMatterWeeklyUpdateRequest` requires status, progress, and a nonblank
`progressSummary`. The path date must be a Monday. A second write for the same
matter and week updates the existing row.

Weekly `PUT` and `DELETE` call `selectByIdForUpdate` before domain authorization.
Matter update also locks the same `bu_key_matter` row, so owner changes and
weekly writes serialize. The ordinary writer must equal the owner read after
the lock; `createdBy` and historical ownership do not grant access. After A is
replaced by B, A receives `403` and B can create, correct, or delete weekly
updates, including historical rows.

Saving the latest chronological weekly update synchronizes matter status,
progress, and completion time. Editing an older week does not roll back the
current matter. Deleting a weekly update never rewrites the current matter.

A completed matter accepts no new weekly row. If the selected week has no row,
upsert returns exact error `已完成事项无需新增周进展`, inserts nothing, and
leaves the matter unchanged. An authorized writer may correct an existing
weekly row without changing the completed matter's current status, progress,
or completion time. Reopening through matter update clears `completedAt` and
restores eligibility for new weekly rows.

The meeting query includes every non-completed matter plus matters completed
during the selected Monday-to-Sunday range. Ordering is priority, blocked/risk
state, open before completed, manual sort order, planned completion date, ID.

## 6. Validation and Error Matrix

| Condition | HTTP / exact behavior |
| --- | --- |
| Missing, invalid, or expired authentication | `401`; Spring Security message `登录已失效，请重新登录` when it handles the response |
| Authenticated caller lacks every endpoint RBAC permission | `403` before controller/domain execution |
| Access endpoint, qualifying RBAC, no relation | `200`; `canAccess=false`, `canManageAll=false`, `canFeedbackOwn=false` |
| Participant relation without database `view` | `canAccess=false`; relationship alone is insufficient |
| Owner relation without database `feedback` | `canFeedbackOwn=false`; relationship alone is insufficient |
| Read endpoint, qualifying RBAC, no relation | `403`, exact domain message `无权访问大事儿` |
| Matter create by non-manager, if request reaches domain check | `403`, exact domain message `仅可创建本人负责的大事儿` unless request `ownerId` equals caller |
| Matter update by non-manager, if request reaches domain check | `403`, exact domain message `仅可编辑本人负责的大事儿` unless current `owner_id` equals caller after row lock |
| Matter delete by non-manager, if request reaches domain check | `403`, exact domain message `仅管理员可管理大事儿` |
| Weekly write by participant, unrelated owner, former owner, or owner without `feedback` | `403`, exact domain message `仅事项负责人可反馈周进度` |
| `admin`/`yufeng` without `manage` | not a manager; cannot use manager bypass |
| Non-allowlisted username with `manage` | not a manager; matter CRUD is `403` and weekly write still requires current ownership plus `feedback` |
| Matter does not exist | `404`, exact message `大事儿不存在` |
| Owner missing or disabled | `400`, exact message `负责人不存在或已停用` |
| Participant missing or disabled | `400`, exact message `参与人不存在或已停用` |
| Planned date before start | `400`, exact message `计划完成日期不能早于开始日期` |
| Non-completed status with 100% | `400`, exact message `进度达到100%时状态必须为已完成` |
| Completed status below 100% | progress normalizes to 100 |
| Weekly path date is not Monday | `400`, exact message `周进展日期必须为周一` |
| Lock reveals owner changed | `403` `仅事项负责人可反馈周进度`; no weekly mutation |
| Completed matter, target weekly row absent | `400` `已完成事项无需新增周进展`; no insert or matter mutation |
| Completed matter, target weekly row exists | authorized row correction; matter status/progress/completedAt unchanged |

## 7. Good / Base / Bad Cases

- Good: an owner with `view` and `feedback` sees every matter and updates only
  the matter currently owned by that user.
- Good: a participant with `view` sees every matter and meeting entry, including
  unrelated matters, but has no weekly write authority.
- Good: an owner with `view` and `feedback` edits the details of matters they
  currently own (`PUT /api/key-matters/{id}`); ownership is re-checked after
  the row lock, and transferring ownership through the edit requires the
  manager bypass.
- Good: `admin` or `yufeng` with `manage` performs matter CRUD and weekly writes
  for any matter.
- Base: access for a user with no relation returns `200` and false booleans;
  list/detail/meeting for that same user returns `403 无权访问大事儿`.
- Base: an update from a stale client omits `participantIds`; all existing
  relations remain and a changed owner is added.
- Bad: an explicit empty update is treated as omitted and preserves stale
  participants. It must instead replace the set with owner-only.
- Bad: authorization is checked before acquiring the matter lock. A concurrent
  owner change could otherwise let the former owner write after losing access.
- Bad: a completed matter receives a missing weekly row. The write must fail
  with `已完成事项无需新增周进展` and insert nothing.

## 8. Tests Required

- `BuKeyMatterAccessServiceTest`: manager requires username plus `manage`;
  owner, participant, and unrelated capability combinations; read/manage/
  feedback/edit exact denial messages; owner without `feedback` is denied;
  `requireEdit` allows the current owner with `view`+`feedback` and denies
  participants, former owners, disabled users, and owners lacking `feedback`.

- `BuKeyMatterParticipantServiceTest`: create null behavior and explicit empty
  `participantIds` producing owner-only; create list normalization; update null
  preserves and adds owner; explicit empty replaces with owner-only; an explicit
  update list that omits owner and duplicates a participant inserts the
  deterministic deduplicated `[participant, owner]` relation order; disabled/
  missing participant; matter delete lock and participant cleanup.
- `BuKeyMatterServiceTest`: creation defaults, validation, completion
  transitions, same-week upsert, historical isolation, meeting inclusion/order,
  completed-row correction, completed-row creation denial, and reopen behavior.
- `BuKeyMatterLockIntegrationTest`: completed-matter race blocks then rejects
  insertion; owner-change race blocks, rejects the old owner after commit, and
  permits the new owner.
- `ControllerSecurityContractTest`: no class username allowlist; exact method
  permission arrays; participant entity/DTO/view contracts; 403 exception
  mapping; V33 table, unique key, indexes, FKs, backfill, and permissions.
- `PermissionInterceptorUsernameTest`: remains a regression for modules that
  still use `@RequireUsername`; key-matter endpoints must not reintroduce it.
- `frontend/tests/key-matter-participant-access.spec.ts`: dynamic route/menu,
  manager participant form, owner-only feedback, participant read-only state,
  403 recovery, stale response handling, re-entry, and 1024 presentation layout.
- `frontend/tests/key-matters.spec.ts`: existing register/detail/weekly/meeting
  and completed-matter behavior remains green.
- `frontend/tests/navigation-permissions.spec.ts`: unrelated execution user has
  false access, hidden menu, and direct-route redirect.

## 9. Wrong vs Correct

Wrong: treat `BUSINESS_OWNER`, a hidden frontend menu, or possession of one
RBAC code as sufficient authorization; check ownership before locking; or make
an omitted update array erase existing participants.

Correct: use RBAC OR checks to enter the controller, enforce current domain
relations in the service, require username plus `manage` for manager bypass,
authorize weekly writes against the locked row, and distinguish omitted update
payloads from explicit participant replacement.
