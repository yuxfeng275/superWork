# 大事儿个人快捷筛选 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在大事儿登记列表增加互斥的“我的事项”和“我参与的事项”快捷筛选，并保持概览、分页、现有筛选和权限行为一致。

**Architecture:** 复用页面已经加载的完整 `allMatters` 及每条事项的 `ownerId/participants`，在服务端普通筛选结果 `matters` 之上增加一个前端 `personalScope` 派生层。分页和表格使用个人筛选后的集合，概览与快捷筛选数量继续使用完整台账；项目、负责人和普通查询会清空个人筛选，避免隐藏条件叠加。

**Tech Stack:** Vue 3、TypeScript、Pinia、Element Plus、Playwright、Vite

**Scope:** 仅修改大事儿页面、对应 E2E 和前端 UI 契约；不修改后端、数据库、访问权限或周进度写入规则。

---

## File Map

- Modify: `frontend/src/views/KeyMattersView.vue` — 个人筛选状态、集合派生、快捷入口、分页和空态。
- Modify: `frontend/tests/key-matter-participant-access.spec.ts` — 负责人/参与人集合互斥、数量、切换、权限和移动端。
- Modify: `frontend/tests/key-matters.spec.ts` — 现有概览、项目/负责人快捷筛选和分页清空个人条件的回归。
- Modify: `.trellis/spec/frontend/key-matter-management-ui.md` — 个人快捷筛选可执行契约和测试点。

---

## Task 1: Personal Quick Filter RED Tests

**Files:**
- Modify: `frontend/tests/key-matter-participant-access.spec.ts`
- Modify: `frontend/tests/key-matters.spec.ts`

- [ ] **Step 1: Extend participant-access fixture with four disjoint matters**

Use current user `id=7` and create fixture records that prove the sets are mutually exclusive:

```ts
const ownedMatter = featureMatter({
  id: 51,
  title: '本人负责事项',
  ownerId: 7,
  projectId: 31
})

const participatingMatter = {
  ...featureMatter({
    id: 52,
    title: '本人参与事项',
    ownerId: 16,
    projectId: 31
  }),
  participants: [
    { userId: 16, username: 'owner-sixteen', realName: '负责人乙' },
    { userId: 7, username: 'owner-seven', realName: '负责人甲' }
  ]
}

const unrelatedMatter = featureMatter({
  id: 53,
  title: '本人无关事项',
  ownerId: 16,
  projectId: 32
})
unrelatedMatter.participants = [unrelatedMatter.participants[0]]
```

The current owner is also present in `ownedMatter.participants` through the existing fixture helper. This proves ownership wins over participation.

- [ ] **Step 2: Add RED test for mutually exclusive personal filters**

Add test `个人快捷筛选区分本人负责和仅参与事项`:

```ts
await page.goto('/key-matters')
const rail = page.getByRole('complementary', { name: '列表快速筛选' })

await expect(rail.getByRole('button', { name: /我的事项/ })).toContainText('1')
await expect(rail.getByRole('button', { name: /我参与的事项/ })).toContainText('1')

await rail.getByRole('button', { name: /我的事项/ }).click()
await expect(table.getByText('本人负责事项')).toBeVisible()
await expect(table.getByText('本人参与事项')).toHaveCount(0)
await expect(table.getByText('本人无关事项')).toHaveCount(0)

await rail.getByRole('button', { name: /我参与的事项/ }).click()
await expect(table.getByText('本人参与事项')).toBeVisible()
await expect(table.getByText('本人负责事项')).toHaveCount(0)
await expect(table.getByText('本人无关事项')).toHaveCount(0)
```

Also assert the active button class changes and `全部事项` restores all records.

- [ ] **Step 3: Add RED test for overview, clearing and pagination**

With 12+ owned/participating records:

```ts
await expect(overview.locator('.summary-cell.all strong')).toHaveText(totalCount)
await rail.getByRole('button', { name: /我的事项/ }).click()
await expect(overview.locator('.summary-cell.all strong')).toHaveText(totalCount)
await expect(pagination).toContainText(`共 ${ownedCount} 项`)
```

Navigate to page 2 before switching and assert the active page returns to 1. Then verify:

- clicking a project quick filter clears the personal active state;
- clicking a responsible-person quick filter clears it;
- clicking “查询” after setting keyword clears it;
- clicking “重置” clears it;
- clicking “全部事项” clears it.

- [ ] **Step 4: Add RED test for personal empty states**

For a user with access but no owned or non-owner participating records:

```ts
await rail.getByRole('button', { name: /我的事项/ }).click()
await expect(page.getByText('暂无我负责的事项')).toBeVisible()
await rail.getByRole('button', { name: /我参与的事项/ }).click()
await expect(page.getByText('暂无我参与的事项')).toBeVisible()
```

Assert the empty state does not contain “点击右上角新增事项”.

- [ ] **Step 5: Add mobile overflow assertion**

At 320px and 390px, activate each personal filter and assert:

```ts
expect(await page.evaluate(() =>
  document.documentElement.scrollWidth > document.documentElement.clientWidth
)).toBe(false)
```

- [ ] **Step 6: Run tests and confirm RED**

Start an ephemeral Vite server from `frontend` and run:

```bash
npx playwright test tests/key-matter-participant-access.spec.ts \
  --grep '个人快捷筛选|个人筛选空态'
```

Expected: tests fail because “我的事项”和“我参与的事项”入口不存在, not because of fixture/mocking errors.

- [ ] **Step 7: Commit RED tests**

```bash
git add frontend/tests/key-matter-participant-access.spec.ts frontend/tests/key-matters.spec.ts
git commit -m "test(key-matters): cover personal quick filters"
```

---

## Task 2: Personal Filter State, Data Pipeline and UI

**Files:**
- Modify: `frontend/src/views/KeyMattersView.vue`

- [ ] **Step 1: Add state and current-user predicates**

Near existing list state add:

```ts
type PersonalScope = '' | 'owned' | 'participating'
const personalScope = ref<PersonalScope>('')

function isOwnedByCurrentUser(matter: BuKeyMatter) {
  const userId = currentUserId.value
  return userId !== undefined && matter.ownerId === userId
}

function isParticipatedByCurrentUser(matter: BuKeyMatter) {
  const userId = currentUserId.value
  return userId !== undefined
    && matter.ownerId !== userId
    && Boolean(matter.participants?.some(participant => participant.userId === userId))
}
```

Do not use `matterParticipants(matter)` for the participation predicate because that helper synthesizes the owner for old responses.

- [ ] **Step 2: Add complete-data counts and visible collection**

```ts
const ownedMatters = computed(() => allMatters.value.filter(isOwnedByCurrentUser))
const participatingMatters = computed(() =>
  allMatters.value.filter(isParticipatedByCurrentUser))

const visibleMatters = computed(() => {
  if (personalScope.value === 'owned') {
    return matters.value.filter(isOwnedByCurrentUser)
  }
  if (personalScope.value === 'participating') {
    return matters.value.filter(isParticipatedByCurrentUser)
  }
  return matters.value
})
```

Change `pagedMatters`, pagination total/count text and page-clamping calculations from `matters.value` to `visibleMatters.value`.

- [ ] **Step 3: Add personal-scope reset helpers**

```ts
function clearPersonalScope() {
  personalScope.value = ''
}

async function applyPersonalScope(scope: Exclude<PersonalScope, ''>) {
  Object.assign(filters, {
    keyword: '',
    status: '',
    priority: '',
    ownerId: undefined,
    projectId: undefined
  })
  personalScope.value = scope
  resetCurrentPage()
  await loadMatters()
}
```

Update `listFilterActive` to include `personalScope !== ''`.

Update these paths to clear personal scope before existing behavior:

- `applyQuickListFilter` for all/project/owner.
- explicit query handler.
- `resetFilters`.

If the existing “查询” directly invokes `loadMatters`, introduce:

```ts
function applyFilters() {
  clearPersonalScope()
  resetCurrentPage()
  loadMatters()
}
```

and bind the button to it.

- [ ] **Step 4: Preserve active scope across refresh**

Do not clear `personalScope` inside `loadMatters`, `loadMilestones`, `refreshActiveMode`, or the refresh toolbar action. After records refresh, call the visible-data clamp logic so a shorter personal set cannot leave an invalid page.

- [ ] **Step 5: Add personal quick filter UI above project groups**

Inside `list-filter-rail`, after “全部事项” and before project groups add two buttons using the existing `list-filter-item` structure and Element Plus icons already imported or available globally:

```vue
<button
  type="button"
  class="list-filter-item personal"
  :class="{ active: personalScope === 'owned' }"
  @click="applyPersonalScope('owned')"
>
  <span class="list-filter-icon owner"><el-icon><UserFilled /></el-icon></span>
  <span><strong>我的事项</strong><small>{{ ownedMatters.length }} 项由我负责</small></span>
</button>

<button
  type="button"
  class="list-filter-item personal"
  :class="{ active: personalScope === 'participating' }"
  @click="applyPersonalScope('participating')"
>
  <span class="list-filter-icon participant"><el-icon><User /></el-icon></span>
  <span><strong>我参与的事项</strong><small>{{ participatingMatters.length }} 项协作参与</small></span>
</button>
```

Use a subtle existing blue/green semantic treatment; do not add a new palette or horizontal toolbar.

- [ ] **Step 6: Add contextual empty text**

Add:

```ts
const registerEmptyText = computed(() => {
  if (personalScope.value === 'owned') return '暂无我负责的事项'
  if (personalScope.value === 'participating') return '暂无我参与的事项'
  return canManageAll.value
    ? '暂无大事儿，点击右上角新增事项'
    : '暂无大事儿'
})
```

Bind the table with a dynamic prop:

```vue
<el-table :empty-text="registerEmptyText" ... />
```

Personal empty states must not advertise creation.

- [ ] **Step 7: Update pagination bindings**

Use:

```vue
<span>共 {{ visibleMatters.length }} 项</span>
<el-pagination :total="visibleMatters.length" ... />
```

Keep existing page-size options and local table scrolling.

- [ ] **Step 8: Run focused tests and build**

```bash
cd frontend
npx playwright test tests/key-matter-participant-access.spec.ts \
  --grep '个人快捷筛选|个人筛选空态'
npm run build
```

Expected: focused tests pass and build succeeds.

- [ ] **Step 9: Commit implementation**

```bash
git add frontend/src/views/KeyMattersView.vue
git commit -m "feat(key-matters): add personal quick filters"
```

---

## Task 3: Contract Sync and Full Regression

**Files:**
- Modify: `.trellis/spec/frontend/key-matter-management-ui.md`
- Modify: `frontend/tests/key-matters.spec.ts` only if existing selector expectations need alignment.

- [ ] **Step 1: Update frontend contract**

Add a “Personal quick filters” section documenting:

```markdown
- owned means current user is `ownerId`;
- participating means current user appears in `participants` and is not owner;
- collections are mutually exclusive;
- counts and summary use complete `allMatters`;
- table/pagination use personal-filtered visible matters;
- project/owner/all/query/reset clear personal scope;
- refresh preserves personal scope;
- exact empty labels.
```

Add the Playwright test points from Task 1.

- [ ] **Step 2: Run complete frontend suite**

Start ephemeral Vite and run:

```bash
cd frontend
npx playwright test
npm run build
```

Expected: all existing and new tests pass; only existing large-chunk warning is allowed.

- [ ] **Step 3: Run diff checks**

```bash
git diff --check
git status -sb
```

Expected: no whitespace errors and only intended files changed.

- [ ] **Step 4: Commit contract**

```bash
git add .trellis/spec/frontend/key-matter-management-ui.md \
  frontend/tests/key-matters.spec.ts
git commit -m "docs(key-matters): record personal filter contract"
```

---

## Task 4: Merge, Deploy and Production Verification

**Files/Artifacts:**
- Build: `frontend/dist/`
- Remote: `/home/openclaw/superwork-claude-sp/frontend/dist/`
- Marker: `/home/openclaw/superwork-claude-sp/DEPLOYED_COMMIT`

- [ ] **Step 1: Final local verification**

```bash
cd frontend
npx playwright test
npm run build
```

Expected: full suite and production build pass.

- [ ] **Step 2: Merge and push master**

Use the feature worktree workflow, merge after review, rerun focused tests on merged `master`, then:

```bash
git push origin master
```

- [ ] **Step 3: Back up current frontend**

```bash
TS=$(date +%Y%m%d-%H%M%S)
ssh 241 "BACKUP=/home/openclaw/deploy-backups/superwork-key-matter-personal-filter-$TS; mkdir -p \"\$BACKUP\"; cp -a /home/openclaw/superwork-claude-sp/frontend/dist \"\$BACKUP/frontend-dist\"; cp /home/openclaw/superwork-claude-sp/DEPLOYED_COMMIT \"\$BACKUP/DEPLOYED_COMMIT\""
```

No backend/database restart is required.

- [ ] **Step 4: Sync and rebuild frontend only**

```bash
rsync -az --delete frontend/dist/ \
  241:/home/openclaw/superwork-claude-sp/frontend/dist/
rsync -az frontend/src/views/KeyMattersView.vue \
  241:/home/openclaw/superwork-claude-sp/frontend/src/views/KeyMattersView.vue
ssh 241 'cd /home/openclaw/superwork-claude-sp/docker && docker compose -f docker-compose.241.yml build frontend && docker compose -f docker-compose.241.yml up -d frontend'
```

- [ ] **Step 5: Production smoke**

With a real owner/participant account:

- personal filter counts match API records;
- “我的事项” contains owned but not participating-only records;
- “我参与的事项” contains participating-only but not owned records;
- “全部事项” restores all records;
- summary values do not change;
- empty labels are correct for a no-result account;
- 390px has no page-level overflow.

Run production-applicable Playwright tests with `PLAYWRIGHT_BASE_URL` and verify frontend HTTP 200.

- [ ] **Step 6: Update marker and rollback record**

```bash
COMMIT=$(git rev-parse --short HEAD)
ssh 241 "printf '$COMMIT' > /home/openclaw/superwork-claude-sp/DEPLOYED_COMMIT"
```

Rollback restores the backed-up frontend dist and rebuilds only the frontend container.

---

## Plan Self-Review

- Collection semantics match the approved mutually-exclusive option A.
- Owner-as-participant does not leak into participating-only scope.
- Complete counts and summary remain independent of personal filtering.
- Existing server-side ordinary filters remain unchanged.
- Hidden condition combinations are cleared in every entry path.
- Refresh preserves active personal scope.
- Empty-state copy does not advertise unauthorized creation.
- No backend or database changes are introduced.
- Existing participant/owner permissions and completed-matter rules remain unchanged.
