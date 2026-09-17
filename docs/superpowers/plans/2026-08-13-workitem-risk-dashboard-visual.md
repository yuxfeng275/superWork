# Work Item Risk Dashboard Visual Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat overdue statistics presentation with a compact, responsive risk dashboard and improve work-item date readability.

**Architecture:** `WorkItemAnalysisPanel.vue` owns all overdue visualization directly from `WorkItemAnalysis`; each view only supplies ordinary distributions. Shared CSS-based bars avoid a chart dependency. View-level detail rows retain their current structures but use consistent date/risk styling.

**Tech Stack:** Vue 3 Composition API, TypeScript, Element Plus icons, scoped CSS, Playwright.

---

### Task 1: Lock the visual contract in E2E tests

**Files:**
- Modify: `frontend/tests/yunxiao-workitems.spec.ts`

- [ ] Assert each page has one `.risk-dashboard`, visible overdue metrics, age composition, and ranked risk data.
- [ ] Assert overdue project/owner/age titles are not repeated in `.analysis-grid`.
- [ ] Assert detail rows use `.overdue-pill` and display creation and due dates.
- [ ] Run `cd frontend && npx playwright test tests/yunxiao-workitems.spec.ts`; expect the new selectors to fail before implementation.

### Task 2: Build the shared risk dashboard

**Files:**
- Modify: `frontend/src/components/WorkItemAnalysisPanel.vue`

- [ ] Split ordinary and overdue presentation responsibilities.
- [ ] Add computed age segments, top-three project rows, and top-three owner rows.
- [ ] Render dominant overdue/missing-plan metrics, segmented age bar, and rankings.
- [ ] Add empty-risk state and responsive desktop/tablet/mobile CSS.
- [ ] Keep standard distribution click behavior and completion-rate header unchanged.

### Task 3: Remove duplicate overdue panels and polish detail dates

**Files:**
- Modify: `frontend/src/views/RequirementsView.vue`
- Modify: `frontend/src/views/TasksView.vue`
- Modify: `frontend/src/views/DefectsView.vue`

- [ ] Remove overdue project/owner/age rows from `analysisSections` in each view.
- [ ] Render creation dates as subdued metadata.
- [ ] Render due dates as the primary date and overdue days as `.overdue-pill`.
- [ ] Ensure missing dates read `未设置计划` and mobile layouts do not overflow.

### Task 4: Verify and deploy

**Files:**
- Modify only if verification reveals a defect.

- [ ] Run `cd frontend && npm run build` and expect success.
- [ ] Run `cd frontend && npx playwright test tests/yunxiao-workitems.spec.ts` and expect 3 passing tests.
- [ ] Capture desktop and mobile screenshots for all three pages and inspect overlap/readability.
- [ ] Sync verified `frontend/dist` to `241`, rebuild the frontend image without cache, and restart only frontend.
- [ ] Run the same E2E suite against `http://192.168.1.241:18080` and verify all three routes return HTTP 200.
