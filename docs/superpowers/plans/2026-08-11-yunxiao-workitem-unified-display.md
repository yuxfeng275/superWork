# Yunxiao Work Item Unified Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge read-only Yunxiao requirements and tasks into the existing business lists and add a separate read-only Yunxiao defect page.

**Architecture:** Extend the Yunxiao cache with cloud-space and normalized fields, then add a backend query layer that returns typed, permission-filtered, deduplicated overview records. Keep all local CRUD APIs unchanged and let the frontend render source-aware actions.

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, Flyway, JUnit/Mockito, Vue 3, TypeScript, Element Plus, Vitest/Playwright.

---

### Task 1: Cache and status contract

**Files:**
- Create: `backend/src/main/resources/db/migration/V25__extend_yunxiao_workitem_cache.sql`
- Create: `backend/src/main/java/com/bu/management/constant/YunxiaoWorkItemConstants.java`
- Modify: `backend/src/main/java/com/bu/management/entity/YunxiaoWorkitemCache.java`
- Modify: `backend/src/main/java/com/bu/management/entity/YunxiaoProjectMapping.java`
- Test: `backend/src/test/java/com/bu/management/service/YunxiaoIntegrationServiceTest.java`

- [ ] Add failing tests proving category/status normalization and cloud project ID/source timestamps are persisted.
- [ ] Run `cd backend && mvn -Dtest=YunxiaoIntegrationServiceTest test` and confirm the new assertions fail.
- [ ] Add migration columns/indexes and typed constants for `Req`, `Task`, `Bug` and normalized status groups.
- [ ] Implement cache field extraction and normalization, then rerun the focused test.

### Task 2: Full-history synchronization

**Files:**
- Modify: `backend/src/main/java/com/bu/management/integration/YunxiaoClient.java`
- Modify: `backend/src/main/java/com/bu/management/service/YunxiaoIntegrationService.java`
- Modify: `backend/src/main/java/com/bu/management/controller/YunxiaoIntegrationController.java`
- Test: `backend/src/test/java/com/bu/management/integration/YunxiaoClientTest.java`
- Test: `backend/src/test/java/com/bu/management/service/YunxiaoIntegrationServiceTest.java`

- [ ] Add failing tests for initial full history, shared-space single execution, subsequent incremental lookback, and stale-row reconciliation.
- [ ] Run the two focused test classes and verify failures are caused by the missing full-sync behavior.
- [ ] Add paged full-search support, `fullSyncedAt`, shared-space locking, and a non-blocking full-sync trigger/status response while preserving existing incremental calls.
- [ ] Rerun focused tests and verify all sync lifecycle cases pass.

### Task 3: Unified backend query model

**Files:**
- Create: `backend/src/main/java/com/bu/management/vo/WorkItemOverviewPage.java`
- Create: `backend/src/main/java/com/bu/management/vo/WorkItemOverviewItem.java`
- Create: `backend/src/main/java/com/bu/management/vo/WorkItemFilterOptions.java`
- Create: `backend/src/main/java/com/bu/management/service/YunxiaoWorkItemQueryService.java`
- Create: `backend/src/main/java/com/bu/management/service/RequirementOverviewService.java`
- Modify: `backend/src/main/java/com/bu/management/controller/RequirementController.java`
- Modify: `backend/src/main/java/com/bu/management/service/TaskService.java`
- Modify: `backend/src/main/java/com/bu/management/controller/TaskController.java`
- Create: `backend/src/main/java/com/bu/management/controller/DefectController.java`
- Test: `backend/src/test/java/com/bu/management/service/YunxiaoWorkItemQueryServiceTest.java`
- Test: `backend/src/test/java/com/bu/management/service/RequirementOverviewServiceTest.java`

- [ ] Add failing tests for category separation, shared-space deduplication, data permissions, source/status/project/assignee/keyword filters, stable sorting and pagination.
- [ ] Add a failing requirement test proving a linked Yunxiao requirement is merged while an unlinked requirement remains independent.
- [ ] Run focused tests and confirm expected failures.
- [ ] Implement typed overview records and query/merge services without exposing `rawJson`.
- [ ] Add the requirement overview and defect endpoints and extend the task overview endpoint.
- [ ] Rerun focused tests and controller security contract tests.

### Task 4: Frontend typed API and source-aware existing pages

**Files:**
- Create: `frontend/src/types/work-item.ts`
- Modify: `frontend/src/utils/api.ts`
- Modify: `frontend/src/views/RequirementsView.vue`
- Modify: `frontend/src/views/TasksView.vue`
- Test: `frontend/tests/yunxiao-workitems.spec.ts`

- [ ] Add failing Playwright assertions for local/cloud source labels, linked requirement deduplication, cloud read-only actions, and local task status editing.
- [ ] Run `cd frontend && npx playwright test tests/yunxiao-workitems.spec.ts --project=chromium` and confirm failures.
- [ ] Add typed overview APIs and adapt requirement/task rendering, filters and detail behavior to `recordKey/source/readOnly`.
- [ ] Rerun the focused E2E test and existing requirement/task interaction tests.

### Task 5: Defect management page

**Files:**
- Create: `frontend/src/views/DefectsView.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/MainLayout.vue`
- Modify: `frontend/src/utils/api.ts`
- Modify: `backend/src/main/resources/db/migration/V25__extend_yunxiao_workitem_cache.sql`
- Test: `frontend/tests/yunxiao-workitems.spec.ts`
- Test: `frontend/tests/navigation-permissions.spec.ts`

- [ ] Add failing E2E coverage for navigation, summary, filters, pagination, detail drawer and stale/error/empty states.
- [ ] Add the defect permission/menu seed and verify permission tests fail before UI implementation.
- [ ] Implement the responsive defect page with Element Plus controls and no local write actions.
- [ ] Run focused E2E tests at desktop and mobile widths.

### Task 6: Regression, specification and deployment

**Files:**
- Modify: `.trellis/spec/backend/index.md`
- Create: `.trellis/spec/backend/yunxiao-workitem-query-contract.md`
- Modify: `.trellis/spec/frontend/index.md`
- Create: `.trellis/spec/frontend/yunxiao-workitem-ui.md`

- [ ] Run `cd backend && mvn test` and require zero failures.
- [ ] Run `cd frontend && npm run build` plus requirement/task/defect/navigation Playwright specs.
- [ ] Review backend/frontend/cross-layer guidelines and correct any violations.
- [ ] Back up the `241` database, deploy backend and frontend with `docker compose -f docker-compose.241.yml up -d --build backend frontend`.
- [ ] Verify Flyway V25, backend health and `http://192.168.1.241:18080/requirements`, `/tasks`, and `/defects`.
- [ ] Trigger the initial full sync, wait for completion, verify `Req/Task/Bug` counts and confirm a linked requirement is not duplicated.
