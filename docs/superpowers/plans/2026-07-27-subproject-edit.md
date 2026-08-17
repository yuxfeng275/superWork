# Subproject Edit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a discoverable edit action for every subproject and keep its full path correct after renaming.

**Architecture:** Reuse the existing Vue project edit dialog and `PUT /api/projects/{id}` client method. Add a focused subproject edit icon in the card UI, then correct `ProjectService.update` so it compares the old name before mutating the entity and recalculates `fullPath`.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Playwright, Spring Boot, JUnit 5, Mockito

---

### Task 1: Frontend Regression Test

**Files:**
- Modify: `frontend/tests/base-management.spec.ts`

- [ ] Add a child project to `mockProjects` under the main project.
- [ ] Intercept `PUT /api/projects/12` and capture its JSON payload.
- [ ] Add a test that clicks the child edit icon, verifies prefilled fields, changes the name, submits, and asserts the captured payload contains the child project ID data.
- [ ] Run `PLAYWRIGHT_BASE_URL=http://127.0.0.1:5175 npx playwright test tests/base-management.spec.ts --reporter=line`.
- [ ] Confirm the new test fails because the child edit action is absent.

### Task 2: Backend Regression Test

**Files:**
- Modify: `backend/src/test/java/com/bu/management/service/ProjectServiceTest.java`

- [ ] Add `updateRenamedChildRefreshesFullPath`.
- [ ] Mock the existing child, business line, parent, and allowed manager.
- [ ] Call `projectService.update` with a new child name.
- [ ] Assert the returned project has `fullPath` equal to `皇家项目/新子项目`.
- [ ] Run `mvn -Dtest=ProjectServiceTest test`.
- [ ] Confirm the test fails because the current implementation compares the name after overwriting it.

### Task 3: Implement the Child Edit Action

**Files:**
- Modify: `frontend/src/views/ProjectView.vue`

- [ ] Import Element Plus `Edit` icon.
- [ ] Split each child chip into a name action and an icon edit action.
- [ ] Route the name action to `openDrawer(sub)` and the edit action to `openEdit(sub)`.
- [ ] Add an “编辑子项目” tooltip, accessible label, fixed icon dimensions, hover, and focus-visible styles.
- [ ] Re-run the focused Playwright test and confirm it passes.

### Task 4: Correct Project Path Updates

**Files:**
- Modify: `backend/src/main/java/com/bu/management/service/ProjectService.java`

- [ ] Capture `boolean nameChanged = !request.getName().equals(project.getName())` before assigning the new name.
- [ ] Call `updateFullPath(project, request.getName())` only when `nameChanged`.
- [ ] Re-run `mvn -Dtest=ProjectServiceTest test` and confirm it passes.

### Task 5: Verification

**Files:**
- Verify: `frontend/src/views/ProjectView.vue`
- Verify: `backend/src/main/java/com/bu/management/service/ProjectService.java`

- [ ] Run `npm run build` in `frontend`.
- [ ] Run the focused Playwright test.
- [ ] Run `mvn test` in `backend`.
- [ ] Open `http://127.0.0.1:5175/projects` in a real browser, edit a child project, and verify the updated name and path with API or database evidence.
