# Access Control Contract

This document defines the authentication and authorization boundary shared by
Spring Security, `PermissionInterceptor`, controller annotations, domain access
services, and frontend route visibility.

## Request Identity

- `POST /api/auth/login` is the only anonymous authentication API.
- `GET /actuator/health` is anonymous and exposes health only.
- `JwtAuthenticationFilter` validates `Authorization: Bearer <accessToken>` and
  writes `userId`, `username`, and `role` request attributes.
- Controllers that persist or authorize an actor must read the authenticated
  request attributes; they must not use constants or accept actor identity from
  a request body.
- Missing, invalid, or expired authentication returns HTTP `401`. Spring
  Security's JSON message is `登录已失效，请重新登录` when its entry point handles
  the response.

## Permission Enforcement

- Every mapped controller method except login must have an effective
  `@RequirePermission`, either on the method or its controller class.
- Multiple codes in one annotation use OR semantics.
- `PermissionInterceptor` resolves current permissions from the database. A
  role claim in a valid JWT does not replace the database permission check.
- A method annotation narrows or replaces its class-level permission.
- RBAC permission allows entry to a controller; modules with domain relation
  rules must perform those checks separately and return domain-specific 403s.
- Frontend visibility and route guards are usability controls only. Backend
  permission and domain checks remain authoritative for direct HTTP calls and
  stale clients.

## Endpoint Matrix

Existing module boundaries remain unchanged except for the key-matter rows
listed below.

| Request | Required RBAC permission (OR) | Domain condition | Anonymous | Authenticated failure | Authorized result |
| --- | --- | --- | --- | --- | --- |
| `POST /api/auth/register` with `RegisterRequest` | `system:user:create` | existing registration role boundary | `401` | `403` | validation or success |
| `GET /api/users` | `system:user:list` / `org:view` | none | `401` | `403` | success |
| `POST /api/users` | `system:user:create` | none | `401` | `403` | validation or success |
| `POST /api/projects` | `org:edit` | none | `401` | `403` | validation or success |
| `GET /api/projects` | `project:view` | none | `401` | `403` | success |
| `GET /actuator/health` | public | none | `200`, `{"status":"UP"}` | same | same |
| unknown public resource | public | none | `404` | same | same |
| `GET /api/key-matters/access` | `bu:key-matter:view` / `bu:key-matter:feedback` / `bu:key-matter:manage` | none | `401` | RBAC miss `403`; no relation is not an error | `200` capability booleans, including all false |
| `GET /api/key-matters` | `bu:key-matter:view` / `bu:key-matter:manage` | manager or participant in at least one matter | `401` | RBAC miss `403`; relation miss `403 无权访问大事儿` | full list, not relation-filtered |
| `GET /api/key-matters/{id}` | `bu:key-matter:view` / `bu:key-matter:manage` | manager or participant in at least one matter | `401` | RBAC miss `403`; relation miss `403 无权访问大事儿` | requested detail, even when caller is related to another matter |
| `GET /api/key-matters/meeting` | `bu:key-matter:view` / `bu:key-matter:manage` | manager or participant in at least one matter | `401` | RBAC miss `403`; relation miss `403 无权访问大事儿` | full meeting result |
| key-matter `POST` matter create | `bu:key-matter:feedback` / `bu:key-matter:manage` | non-manager: `view` + `feedback` **and** request `ownerId` equals caller | `401` | RBAC miss `403`; domain miss `403 仅可创建本人负责的大事儿` | validation or success |
| key-matter `PUT/{id}` matter update | `bu:key-matter:feedback` / `bu:key-matter:manage` | after matter-row lock: manager (`admin`/`yufeng` + `manage`), or non-manager with `view` + `feedback` **and** `bu_key_matter.owner_id` equals caller | `401` | RBAC miss `403`; domain miss `403 仅可编辑本人负责的大事儿` | validation or success |
| key-matter `DELETE/{id}` matter delete | `bu:key-matter:manage` | manager bypass only (`admin`/`yufeng` + `manage`) | `401` | RBAC miss `403`; domain miss `403 仅管理员可管理大事儿` | success |
| key-matter weekly `PUT/DELETE` | `bu:key-matter:feedback` / `bu:key-matter:manage` | after matter-row lock: current owner, or `admin`/`yufeng` with `bu:key-matter:manage` | `401` | RBAC miss `403`; domain miss `403 仅事项负责人可反馈周进度` | validation or success |

`RegisterRequest` fields remain `username`, `password`, `realName`, `role`,
`email`, and `phone`. The requested `role` never grants permission to submit
the registration request.

## Key-Matter Domain Rules

- There is no fixed username boundary for key-matter reads.
- A current owner is also a participant by database/service invariant.
- A participant or owner with `view` may read all matters, all details, and the
  complete meeting view. The result is not filtered to that user's relations.
- A user with no current participant relation receives HTTP `200` with false
  capabilities from `/access`, but receives `403 无权访问大事儿` from the
  list, detail, and meeting endpoints.
- A participant who is not the current owner is read-only. Possessing
  `feedback` does not permit writes to another owner's matter.
- Matter edit authority follows the same ownership rule as weekly feedback:
  after `SELECT ... FOR UPDATE`, a non-manager with `view` + `feedback` may
  update only matters whose current `bu_key_matter.owner_id` equals the caller;
  former ownership and `createdBy` are irrelevant. Owner transfer via update
  still requires the manager bypass.
- Weekly feedback authority uses the current `bu_key_matter.owner_id` after
  `SELECT ... FOR UPDATE`; weekly `createdBy` and former ownership are irrelevant.
- Manager bypass requires both an allowlisted username (`admin`/`yufeng`) and
  `bu:key-matter:manage`. Neither factor alone is sufficient.
- A non-allowlisted user who happens to possess `manage` is not a manager.
- The key-matter controller must have no class-level `@RequireUsername` or
  class-level permission; method annotations express the endpoint matrix.

## Frontend Contract

- `frontend/src/constants/roles.ts` remains the default-role navigation matrix
  for other modules.
- Key-matter menu visibility comes from runtime `keyMatterAccess.canAccess`, not
  a username or static role allowlist.
- `frontend/src/router/index.ts` guards both `/key-matters` and
  `/key-matters-meeting` by forcing an access-capability refresh; denied or
  malformed capability data redirects to `/`.
- Login/logout/session changes invalidate cached key-matter capabilities so a
  late response from a prior generation cannot authorize a new session.

## Required Regression Cases

Good:

- `admin` or `yufeng` with `manage` can manage any matter and weekly update.
- A current owner with `view`/`feedback` can see the menu, read all matters, and
  write only matters currently owned by that user.
- A current owner with `view`/`feedback` can also edit the matter details of
  matters they currently own (`PUT /api/key-matters/{id}`); the edit UI shows
  the 编辑事项 action only for those rows and the drawer locks 负责人.
- A participant with `view` can see the menu and read all matters but cannot
  add, edit, or delete weekly feedback.
- An execution role with `org:view` can still load the user directory needed by
  project, requirement, and task forms.

Base:

- A no-relation user with an access-endpoint RBAC code receives `200` and three
  false booleans from `/api/key-matters/access`.
- Anonymous health returns `200`.
- Unknown actuator/resource paths return `404`, not `500`.
- Other module role and permission boundaries remain unchanged.

Bad:

- Anonymous key-matter calls and registration return `401`.
- A no-relation user calling key-matter list/detail/meeting receives
  `403 无权访问大事儿`.
- A participant or an owner targeting another owner's matter receives
  `403 仅事项负责人可反馈周进度`.
- A former owner loses write authority after an owner change commits; the new
  owner gains it.
- A participant (non-owner) attempting a matter edit receives
  `403 仅可编辑本人负责的大事儿`.
- `admin`/`yufeng` without `manage`, and a non-allowlisted username with
  `manage`, cannot use manager operations.
- An execution role cannot register a user or mutate a project (`403`).
- Direct navigation to hidden system/statistics routes redirects to `/`.
- A no-relation user cannot see or directly enter either key-matter route.
- Stored requirement HTML removes scripts, event handlers, and unsafe URLs.

Automated assertions live in:

- `backend/src/test/java/com/bu/management/config/SecurityConfigTest.java`
- `backend/src/test/java/com/bu/management/controller/ControllerSecurityContractTest.java`
- `backend/src/test/java/com/bu/management/service/BuKeyMatterAccessServiceTest.java`
- `backend/src/test/java/com/bu/management/service/BuKeyMatterLockIntegrationTest.java`
- `backend/src/test/java/com/bu/management/BuManagementApplicationTests.java`
- `frontend/tests/key-matter-participant-access.spec.ts`
- `frontend/tests/navigation-permissions.spec.ts`
- `frontend/tests/requirements-detail-data.spec.ts`
