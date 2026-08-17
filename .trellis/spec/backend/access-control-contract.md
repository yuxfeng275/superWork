# Access Control Contract

This document defines the authentication and authorization boundary shared by
Spring Security, `PermissionInterceptor`, controller annotations, and frontend
route visibility.

## Request Identity

- `POST /api/auth/login` is the only anonymous authentication API.
- `GET /actuator/health` is anonymous and exposes health only.
- `JwtAuthenticationFilter` validates `Authorization: Bearer <accessToken>` and
  writes `userId`, `username`, and `role` request attributes.
- Controllers that persist an actor ID must read `@RequestAttribute("userId")`;
  they must not use a constant or accept the actor ID from the request body.

## Permission Enforcement

- Every mapped controller method except login must have an effective
  `@RequirePermission`, either on the method or its controller class.
- Multiple codes in one annotation use OR semantics.
- `PermissionInterceptor` resolves current permissions from the database. A
  role claim in a valid JWT does not replace the database permission check.
- A method annotation narrows or replaces its class-level permission.

## Endpoint Matrix

| Request | Required permission | Anonymous | Execution role without permission | Authorized management role |
| --- | --- | --- | --- | --- |
| `POST /api/auth/register` with `RegisterRequest` | `system:user:create` | `401` | `403` | validation or success |
| `GET /api/users` | `system:user:list` OR `org:view` | `401` | success with `org:view` | success |
| `POST /api/users` | `system:user:create` | `401` | `403` | validation or success |
| `POST /api/projects` | `org:edit` | `401` | `403` | validation or success |
| `GET /api/projects` | `project:view` | `401` | success when granted | success |
| `GET /actuator/health` | public | `200`, `{"status":"UP"}` | same | same |
| unknown public resource | public | `404` | same | same |
| `/api/key-matters/**` | `bu:key-matter:manage` plus username `admin` or `yufeng` | `401` | `403` | success only for allowlisted username |

`RegisterRequest` fields remain `username`, `password`, `realName`, `role`,
`email`, and `phone`. The requested `role` never grants permission to submit
the registration request.

## Frontend Contract

- `frontend/src/constants/roles.ts` is the default-role navigation matrix.
- `frontend/src/router/index.ts` blocks direct navigation as well as hiding menu
  items in `MainLayout.vue`.
- Frontend visibility is usability only. Backend permission checks are the
  security boundary, including for direct HTTP calls and stale clients.

## Required Regression Cases

Good:

- `admin` can open user management and submit user creation.
- An execution role with `org:view` can load the user directory needed by
  project, requirement, and task forms.

Base:

- Anonymous health returns `200`.
- Unknown actuator/resource paths return `404`, not `500`.

Bad:

- Anonymous registration returns `401`.
- An execution role cannot register a user or mutate a project (`403`).
- Direct navigation to a hidden system/statistics route redirects to `/`.
- A non-allowlisted user cannot see or directly access key-matter UI/API routes.
- Stored requirement HTML removes scripts, event handlers, and unsafe URLs.

Automated assertions live in:

- `backend/src/test/java/com/bu/management/config/SecurityConfigTest.java`
- `backend/src/test/java/com/bu/management/controller/ControllerSecurityContractTest.java`
- `backend/src/test/java/com/bu/management/BuManagementApplicationTests.java`
- `frontend/tests/navigation-permissions.spec.ts`
- `frontend/tests/requirements-detail-data.spec.ts`
