# 大事儿管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an owner-only BU key-matter register with weekly progress history and a projection-ready weekly meeting mode.

**Architecture:** Add two purpose-built MySQL tables behind a focused Spring service and controller. Enforce `admin/yufeng` at both the backend interceptor and Vue route/menu boundary, then expose a single Vue workbench with register and meeting views.

**Tech Stack:** Spring Boot 3.2, MyBatis-Plus, Flyway, JUnit 5/Mockito, Vue 3 TypeScript, Element Plus, Playwright.

---

## File Map

Backend creates V19 migration; `RequireUsername`; two entities and mappers;
matter/weekly request DTOs; matter/weekly view VOs; `BuKeyMatterService` and
`BuKeyMatterController`; service and interceptor tests. Backend modifies
`PermissionInterceptor`, `ControllerSecurityContractTest`, and Trellis specs.

Frontend creates `KeyMattersView.vue` and `key-matters.spec.ts`; it modifies
`api.ts`, `router/index.ts`, `MainLayout.vue`, and Trellis specs.

### Task 1: Domain Contract and Persistence

- [ ] Write `BuKeyMatterServiceTest#createRejectsInvalidDatesAndPersistsValidOwnerProject` first. The intended create API must default priority to `P1`, status to `未开始`, progress to `0`, and use authenticated `createdBy`.
- [ ] Run `cd backend && mvn -q -Dtest=BuKeyMatterServiceTest test`; verify it fails because key-matter classes are missing.
- [ ] Create `V19__add_key_matter_management.sql` with `bu_key_matter` and `bu_key_matter_weekly_update`. Use `project_id ON DELETE SET NULL`, weekly `ON DELETE CASCADE`, and unique `(key_matter_id, week_start_date)`.
- [ ] Create `BuKeyMatter`, `BuKeyMatterWeeklyUpdate`, both mapper interfaces, `BuKeyMatterRequest`, `BuKeyMatterWeeklyUpdateRequest`, `BuKeyMatterView`, and `BuKeyMatterWeeklyUpdateView`.
- [ ] Implement minimal `BuKeyMatterService#create`. Validate active owner, optional project, `P0/P1/P2`, allowed status, date order, and 0-100 progress. Completed forces 100; non-completed rejects 100.
- [ ] Re-run the Task 1 test and verify PASS.

### Task 2: Lifecycle, Weekly History, and Meeting Rules

- [ ] Add failing tests named `updateSetsAndClearsCompletedAt`, `weeklyUpdateUpsertsSameWeekAndSynchronizesLatestMatterState`, `editingOlderWeekDoesNotRollBackCurrentMatterState`, `meetingIncludesOpenMattersAndOnlyThisWeeksCompletedMatters`, and `meetingSortsBlockedAndRiskItemsWithinPriority`.
- [ ] Run the service test and verify failures for missing lifecycle methods.
- [ ] Implement these signatures:

```java
List<BuKeyMatterView> list(String keyword, String status, String priority,
                           Long ownerId, Long projectId);
BuKeyMatterView get(Long id);
BuKeyMatter create(BuKeyMatterRequest request, Long userId);
BuKeyMatter update(Long id, BuKeyMatterRequest request);
void delete(Long id);
BuKeyMatterWeeklyUpdate upsertWeeklyUpdate(
    Long matterId, LocalDate weekStartDate,
    BuKeyMatterWeeklyUpdateRequest request, Long userId);
void deleteWeeklyUpdate(Long matterId, LocalDate weekStartDate);
List<BuKeyMatterView> meeting(LocalDate weekStartDate);
```

- [ ] Batch-load owners, projects, and weekly rows when creating projections. Derive `overdue` and `currentWeekUpdated` server-side.
- [ ] Run `mvn -q -Dtest=BuKeyMatterServiceTest test`; verify all service tests pass.

### Task 3: Owner-Only API Security

- [ ] Write `PermissionInterceptorUsernameTest` first. Assert `admin` and `yufeng` pass, `zhangquncheng` gets 403, and a missing authenticated username gets 401.
- [ ] Extend `ControllerSecurityContractTest` to require both `@RequirePermission("bu:dashboard:view")` and `@RequireUsername({"admin", "yufeng"})` on `BuKeyMatterController`.
- [ ] Run `mvn -q -Dtest=PermissionInterceptorUsernameTest,ControllerSecurityContractTest test`; verify red.
- [ ] Create `RequireUsername` and extend `PermissionInterceptor` so username and permission annotations are both enforced when both exist.
- [ ] Create controller endpoints for list, detail, create, update, delete, weekly upsert/delete, and meeting. Actor IDs come only from request attributes.
- [ ] Run `mvn -q package`; verify all backend tests pass and the JAR is produced.

### Task 4: Frontend Access and API Client

- [ ] Create failing Playwright test `大事儿管理仅admin和于峰可访问`. Mock three login identities; assert admin/yufeng see the nav and another user is redirected from `/key-matters`.
- [ ] Run `cd frontend && npx playwright test tests/key-matters.spec.ts --grep "专属访问" --reporter=line`; verify red.
- [ ] Add typed API interfaces and list/detail/create/update/delete/weekly/meeting methods in `api.ts`.
- [ ] Add `allowedUsernames` to the route and navigation models, create `/key-matters`, and add a minimal page shell.
- [ ] Re-run the access test and verify PASS.

### Task 5: Register View and CRUD

- [ ] Add failing Playwright tests for summary counts, filters, create payload IDs, edit prefill, detail drawer, and delete confirmation.
- [ ] Run `key-matters.spec.ts`; verify the expected missing-control failures.
- [ ] Implement a compact summary band, filters, dense table, semantic status/priority icons, stable progress bars, create/edit drawer, and detail drawer. Reuse Element Plus; add no dependency.
- [ ] Include loading, error, empty, disabled, hover, and focus-visible states.
- [ ] Run `npm run build` and the key-matter spec; verify PASS.

### Task 6: Weekly Updates and Meeting Mode

- [ ] Add failing tests for reverse-chronological history, current-Monday PUT payload, same-week overwrite, open/current-week-completed meeting scope, and “本周待更新”.
- [ ] Run the key-matter spec and verify red.
- [ ] Implement the five-field weekly editor and the `台账 / 周会模式` control. Meeting rows show owner, status, progress, target date, progress summary, issues, next plan, and support needed.
- [ ] Add 1440x900 and 390x844 assertions that `document.documentElement.scrollWidth <= document.documentElement.clientWidth`; tables may scroll only inside their own container.
- [ ] Run `npx playwright test --reporter=line`; verify the full frontend suite passes.

### Task 7: Specs, Deployment, and Production Verification

- [ ] Create `.trellis/spec/backend/key-matter-management-contract.md` with paths, DTOs, access/validation matrices, Good/Base/Bad cases, and test assertions; link from backend index.
- [ ] Create `.trellis/spec/frontend/key-matter-management-ui.md` with page states, API use, access rules, and responsive checks; link from frontend index.
- [ ] Run `git diff --check`, backend package, frontend build, and full Playwright suite.
- [ ] Copy the JAR, V19, frontend `dist`, source, tests, and specs to `/home/openclaw/superwork-claude-sp`.
- [ ] On 241 run `docker compose -f docker-compose.241.yml up -d --build backend frontend` from the `docker` directory.
- [ ] Verify Flyway V19, HTTP 200, container health, admin/yufeng access, other-user API 403, CRUD, weekly history, meeting mode, and desktop/mobile screenshots under `output/playwright/key-matters-*.png`.
