# 大事儿完成后停止周度更新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 已完成的大事儿不再新增、补交或提醒周度更新，同时保留历史周报修正能力，并确保直接 API 和并发请求不能绕过规则。

**Architecture:** 后端在事项更新和周报 upsert 前使用 `SELECT ... FOR UPDATE` 锁定事项行，以数据库当前状态决定是否允许创建周报；已完成事项只允许更新已存在的周报，且历史修正不再同步事项状态。前端集中使用“是否需要周报”判断，统一控制登记列表、详情、周会和演示模式的统计、入口和只读完成状态。

**Tech Stack:** Spring Boot 3.2、MyBatis Plus、MySQL 8、JUnit 5/Mockito、Vue 3、TypeScript、Element Plus、Playwright

---

## File Map

- `backend/src/main/java/com/bu/management/mapper/BuKeyMatterMapper.java`：提供事项行锁查询。
- `backend/src/main/java/com/bu/management/service/BuKeyMatterService.java`：实施完成状态写入限制和历史修正隔离。
- `backend/src/test/java/com/bu/management/service/BuKeyMatterServiceTest.java`：覆盖新增拒绝、历史修正、重新打开和锁查询。
- `frontend/src/views/KeyMattersView.vue`：统一登记列表、详情、周会和演示模式行为。
- `frontend/tests/key-matters.spec.ts`：覆盖完成事项无更新入口、无待更新提示、只读周会及并发恢复。
- `.trellis/spec/backend/key-matter-management-contract.md`：固化 API 写入矩阵和并发约束。
- `.trellis/spec/frontend/key-matter-management-ui.md`：固化完成事项的 UI 状态和测试点。

### Task 1: 后端完成状态约束与行锁

**Files:**
- Modify: `backend/src/test/java/com/bu/management/service/BuKeyMatterServiceTest.java`
- Modify: `backend/src/main/java/com/bu/management/mapper/BuKeyMatterMapper.java`
- Modify: `backend/src/main/java/com/bu/management/service/BuKeyMatterService.java`

- [ ] **Step 1: 写入失败测试**

在 `BuKeyMatterServiceTest` 增加以下用例；首次编译会因 `selectByIdForUpdate` 尚不存在而失败：

```java
@Test
void completedMatterRejectsCreatingWeeklyUpdate() {
    LocalDate week = LocalDate.of(2026, 8, 10);
    BuKeyMatter completed = matter(11L, "P1", "已完成", 100,
            LocalDate.of(2026, 8, 8));
    completed.setCompletedAt(LocalDateTime.of(2026, 8, 8, 18, 0));
    when(matterMapper.selectByIdForUpdate(11L)).thenReturn(completed);
    when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    assertThatThrownBy(() -> service.upsertWeeklyUpdate(
            11L, week, weeklyRequest("已完成", 100, "补录完成周报"), 16L))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("已完成事项无需新增周进展");

    verify(weeklyUpdateMapper, never()).insert(any(BuKeyMatterWeeklyUpdate.class));
    verify(matterMapper, never()).updateById(any(BuKeyMatter.class));
}

@Test
void completedMatterAllowsExistingWeeklyCorrectionWithoutReopeningMatter() {
    LocalDate week = LocalDate.of(2026, 8, 3);
    LocalDateTime completedAt = LocalDateTime.of(2026, 8, 8, 18, 0);
    BuKeyMatter completed = matter(11L, "P1", "已完成", 100,
            LocalDate.of(2026, 8, 8));
    completed.setCompletedAt(completedAt);
    BuKeyMatterWeeklyUpdate existing = weekly(21L, 11L, week, "已完成", 100);
    when(matterMapper.selectByIdForUpdate(11L)).thenReturn(completed);
    when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

    BuKeyMatterWeeklyUpdate saved = service.upsertWeeklyUpdate(
            11L, week, weeklyRequest("推进中", 90, "修正历史说明"), 16L);

    assertThat(saved.getProgressSummary()).isEqualTo("修正历史说明");
    assertThat(completed.getStatus()).isEqualTo("已完成");
    assertThat(completed.getProgress()).isEqualTo(100);
    assertThat(completed.getCompletedAt()).isEqualTo(completedAt);
    verify(weeklyUpdateMapper).updateById(existing);
    verify(matterMapper, never()).updateById(any(BuKeyMatter.class));
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd backend
mvn -Dtest=BuKeyMatterServiceTest test
```

Expected: `COMPILATION ERROR`，提示 `BuKeyMatterMapper` 没有 `selectByIdForUpdate(Long)`。

- [ ] **Step 3: 增加事项行锁查询**

将 `BuKeyMatterMapper` 改为：

```java
package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.BuKeyMatter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BuKeyMatterMapper extends BaseMapper<BuKeyMatter> {

    @Select("SELECT * FROM bu_key_matter WHERE id = #{id} FOR UPDATE")
    BuKeyMatter selectByIdForUpdate(@Param("id") Long id);
}
```

- [ ] **Step 4: 在事务写路径使用行锁并限制新增**

在 `BuKeyMatterService` 增加锁定查找：

```java
private BuKeyMatter findMatterForUpdate(Long id) {
    BuKeyMatter matter = id == null ? null : matterMapper.selectByIdForUpdate(id);
    if (matter == null) {
        throw new ResourceNotFoundException("大事儿不存在");
    }
    return matter;
}
```

将 `update` 的首行查找改为：

```java
BuKeyMatter matter = findMatterForUpdate(id);
```

将 `upsertWeeklyUpdate` 从事项读取到创建判断部分替换为：

```java
validateWeekStart(weekStartDate);
BuKeyMatter matter = findMatterForUpdate(matterId);
List<BuKeyMatterWeeklyUpdate> existingUpdates = weeklyUpdateMapper.selectList(
        new LambdaQueryWrapper<BuKeyMatterWeeklyUpdate>()
                .eq(BuKeyMatterWeeklyUpdate::getKeyMatterId, matterId)
                .orderByDesc(BuKeyMatterWeeklyUpdate::getWeekStartDate));
BuKeyMatterWeeklyUpdate update = existingUpdates.stream()
        .filter(item -> weekStartDate.equals(item.getWeekStartDate()))
        .findFirst()
        .orElseGet(BuKeyMatterWeeklyUpdate::new);
boolean creating = update.getId() == null;
boolean matterAlreadyCompleted = "已完成".equals(matter.getStatus());
if (matterAlreadyCompleted && creating) {
    throw new RuntimeException("已完成事项无需新增周进展");
}
NormalizedWeeklyUpdate normalized = validate(request);
```

将事项同步条件改为：

```java
if (!matterAlreadyCompleted
        && (latestWeek == null || !weekStartDate.isBefore(latestWeek))) {
    synchronizeMatter(matter, normalized.status(), normalized.progress(), now);
    matterMapper.updateById(matter);
}
```

- [ ] **Step 5: 调整既有 mock 并补重新打开回归**

把 `updateSetsAndClearsCompletedAt`、`weeklyUpdateUpsertsSameWeekAndSynchronizesLatestMatterState`、`editingOlderWeekDoesNotRollBackCurrentMatterState` 中写路径的：

```java
when(matterMapper.selectById(11L)).thenReturn(matter);
```

改为：

```java
when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
```

增加重新打开后可新增周报用例：

```java
@Test
void reopenedMatterCanCreateWeeklyUpdate() {
    LocalDate week = LocalDate.of(2026, 8, 10);
    BuKeyMatter reopened = matter(11L, "P1", "推进中", 80,
            LocalDate.of(2026, 8, 28));
    when(matterMapper.selectByIdForUpdate(11L)).thenReturn(reopened);
    when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    service.upsertWeeklyUpdate(
            11L, week, weeklyRequest("推进中", 85, "重新打开后继续推进"), 16L);

    verify(weeklyUpdateMapper).insert(any(BuKeyMatterWeeklyUpdate.class));
    verify(matterMapper).updateById(reopened);
}
```

- [ ] **Step 6: 运行后端定向测试**

Run:

```bash
cd backend
mvn -Dtest=BuKeyMatterServiceTest test
```

Expected: `BUILD SUCCESS`，新增和既有 `BuKeyMatterServiceTest` 全部通过。

- [ ] **Step 7: 提交后端改动**

```bash
git add backend/src/main/java/com/bu/management/mapper/BuKeyMatterMapper.java \
  backend/src/main/java/com/bu/management/service/BuKeyMatterService.java \
  backend/src/test/java/com/bu/management/service/BuKeyMatterServiceTest.java
git commit -m "fix(key-matters): stop updates after completion"
```

### Task 2: 前端完成事项行为测试

**Files:**
- Modify: `frontend/tests/key-matters.spec.ts`

- [ ] **Step 1: 增加完成事项测试数据**

在 `coreMatters` 后增加独立 fixture，不改变默认分页数据：

```ts
const completedMatterWithoutWeeklyUpdate = {
  id: 13,
  title: '完成事项无需周报',
  description: '已通过事项编辑直接完成',
  projectId: 3,
  projectName: '皇家全渠道定制项目',
  ownerId: 7,
  ownerName: '石家乐',
  priority: 'P1',
  status: '已完成',
  progress: 100,
  startDate: '2026-08-03',
  plannedCompletionDate: '2026-08-08',
  completedAt: '2026-08-08T18:00:00',
  sortOrder: 2,
  overdue: false,
  currentWeekUpdated: false,
  latestUpdate: null,
  currentWeekUpdate: null,
  weeklyUpdates: []
}
```

- [ ] **Step 2: 添加登记列表、详情和周会只读测试**

新增辅助路由和测试：

```ts
async function routeCompletedMatter(page: import('@playwright/test').Page) {
  await page.unroute('**/api/key-matters**')
  await page.route('**/api/key-matters**', route => {
    const path = new URL(route.request().url()).pathname
    const data = path === '/api/key-matters/13'
      ? completedMatterWithoutWeeklyUpdate
      : [completedMatterWithoutWeeklyUpdate]
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data })
    })
  })
}

test('已完成事项不再要求新增周进展', async ({ page }) => {
  await routeCompletedMatter(page)
  await page.goto('/key-matters')

  const row = page.getByLabel('大事儿列表').getByRole('row', {
    name: /完成事项无需周报/
  })
  await expect(row).toContainText('无需更新')
  await expect(row.getByRole('button', { name: '更新周进展' })).toHaveCount(0)
  await expect(page.getByLabel('事项概览').locator('.summary-cell.pending strong')).toHaveText('0')

  await row.getByText('完成事项无需周报').click()
  const detail = page.locator('.detail-content')
  await expect(detail.getByRole('button', { name: '更新周进展' })).toHaveCount(0)

  await page.goto('/key-matters-meeting')
  const stage = page.getByLabel('周会演示模式')
  await expect(stage).toContainText('本周已完成，无需更新')
  await expect(stage.getByLabel('演示中更新周报')).toHaveCount(0)
  await expect(stage.getByRole('button', { name: /保存并下一项/ })).toHaveCount(0)
})
```

- [ ] **Step 3: 添加并发完成后的恢复测试**

添加一个先返回推进中、保存时返回完成错误、刷新后返回已完成的测试，断言表单关闭并刷新状态：

```ts
test('填写期间事项被完成后关闭新增表单并刷新状态', async ({ page }) => {
  let completed = false
  await page.unroute('**/api/key-matters**')
  await page.route('**/api/key-matters**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'PUT' && path.includes('/weekly-updates/')) {
      completed = true
      return route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ code: 400, message: '已完成事项无需新增周进展' })
      })
    }
    const matter = completed
      ? { ...coreMatters[1], status: '已完成', progress: 100, currentWeekUpdated: false }
      : coreMatters[1]
    const data = path === '/api/key-matters/12' ? matter : [matter]
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data })
    })
  })

  await page.goto('/key-matters')
  const row = page.getByLabel('大事儿列表').getByRole('row', {
    name: /运营平台数据质量治理/
  })
  await row.getByRole('button', { name: '更新周进展' }).click()
  const weekly = page.getByRole('dialog', { name: '更新周进展' })
  await weekly.getByRole('textbox', { name: '本周成果' }).fill('本周已完成')
  await weekly.getByRole('button', { name: '保存周进展' }).click()

  await expect(weekly).toHaveCount(0)
  await expect(page.getByLabel('大事儿列表')).toContainText('已完成')
  await expect(page.getByText('已完成事项无需新增周进展')).toBeVisible()
})
```

- [ ] **Step 4: 运行测试并确认失败**

Run:

```bash
cd frontend
npx playwright test tests/key-matters.spec.ts \
  --grep "已完成事项不再要求|填写期间事项被完成"
```

Expected: FAIL；当前页面仍显示新增按钮、待更新表单，且保存错误后不会关闭并刷新。

- [ ] **Step 5: 提交失败测试**

```bash
git add frontend/tests/key-matters.spec.ts
git commit -m "test(key-matters): cover completed update behavior"
```

### Task 3: 前端登记、详情、周会与恢复逻辑

**Files:**
- Modify: `frontend/src/views/KeyMattersView.vue`

- [ ] **Step 1: 增加统一周报资格判断**

在状态辅助函数附近增加：

```ts
function isCompletedMatter(matter: BuKeyMatter) {
  return matter.status === '已完成'
}

function requiresWeeklyUpdate(matter: BuKeyMatter) {
  return !isCompletedMatter(matter)
}

function isCompletedWeeklyUpdateError(error: unknown) {
  return error instanceof Error
    && error.message.includes('已完成事项无需新增周进展')
}
```

增加编辑状态：

```ts
const weeklyEditingExisting = ref(false)
```

- [ ] **Step 2: 修正周会统计分母**

为 `MeetingGroup` 和 `PresentationGroup` 增加：

```ts
updateRequiredCount: number
```

登记页 `summary` 先计算应更新事项数：

```ts
const updateRequiredCount = allMatters.value.filter(requiresWeeklyUpdate).length
const pendingUpdate = allMatters.value.filter(item =>
  requiresWeeklyUpdate(item) && !item.currentWeekUpdated).length
return {
  total,
  progressing,
  risks,
  pendingUpdate,
  updateRequiredCount,
  progressingRate: total ? Math.round(progressing / total * 100) : 0,
  riskRate: total ? Math.round(risks / total * 100) : 0,
  updatedCount: allMatters.value.filter(item => item.currentWeekUpdated).length
}
```

登记页待更新摘要的分母和进度改为：

```vue
<small>{{ summary.updatedCount }}/{{ summary.updateRequiredCount }}</small>
<div class="summary-meter">
  <i :style="{ width: `${summary.updateRequiredCount ? summary.updatedCount / summary.updateRequiredCount * 100 : 100}%` }" />
</div>
```

在分组计算中使用非完成事项作为周报统计分母：

```ts
const updateRequiredMatters = group.matters.filter(requiresWeeklyUpdate)
updatedCount: updateRequiredMatters.filter(matter => Boolean(reportUpdate(matter))).length,
updateRequiredCount: updateRequiredMatters.length,
```

`presentationGroups` 对 `groupedMatters` 明确计算：

```ts
const updateRequiredMatters = groupedMatters.filter(requiresWeeklyUpdate)
return {
  key,
  label: presentationGroupBy.value === 'project'
    ? projectPresentation(groupedMatters[0]).displayName
    : (groupedMatters[0].ownerName || '未指定负责人'),
  matters: groupedMatters,
  riskCount: groupedMatters.filter(matter =>
    ['有风险', '已阻塞'].includes(meetingStatus(matter))).length,
  updatedCount: updateRequiredMatters.filter(matter => Boolean(reportUpdate(matter))).length,
  updateRequiredCount: updateRequiredMatters.length,
  averageProgress: Math.round(
    groupedMatters.reduce((total, matter) => total + meetingProgress(matter), 0)
      / groupedMatters.length
  )
}
```

将两个分组展示位置改为：

```vue
<template v-if="group.updateRequiredCount">
  {{ group.updatedCount }}/{{ group.updateRequiredCount }} 已更新
</template>
<template v-else>无需更新</template>
```

将 `meetingSummary` 替换为：

```ts
const meetingSummary = computed(() => {
  const total = meetingMatters.value.length
  const updateRequiredMatters = meetingMatters.value.filter(requiresWeeklyUpdate)
  const updated = updateRequiredMatters.filter(matter => Boolean(reportUpdate(matter))).length
  const pending = Math.max(updateRequiredMatters.length - updated, 0)
  const risks = meetingMatters.value.filter(matter =>
    ['有风险', '已阻塞'].includes(meetingStatus(matter))).length
  const averageProgress = total
    ? Math.round(meetingMatters.value.reduce((sum, matter) =>
        sum + meetingProgress(matter), 0) / total)
    : 0
  return {
    total,
    updated,
    pending,
    risks,
    averageProgress,
    updatedRate: updateRequiredMatters.length
      ? Math.round(updated / updateRequiredMatters.length * 100)
      : 100,
    pendingRate: updateRequiredMatters.length
      ? Math.round(pending / updateRequiredMatters.length * 100)
      : 0
  }
})
```

把模板中的分组统计：

```vue
{{ group.updatedCount }}/{{ group.matters.length }}
```

改为：

```vue
{{ group.updatedCount }}/{{ group.updateRequiredCount }}
```

- [ ] **Step 3: 阻止完成事项打开新增周报**

将 `openWeekly` 中 update 查找后加入：

```ts
weeklyEditingExisting.value = Boolean(update)
if (isCompletedMatter(matter) && !update) {
  ElMessage.info('本周已完成，无需更新')
  return
}
```

保持历史时间线传入已有 `weekStartDate` 时可打开编辑。

在 `saveWeeklyUpdate` 的 catch 中替换为：

```ts
} catch (error: unknown) {
  ElMessage.error(errorMessage(error, '周进展保存失败'))
  if (!weeklyEditingExisting.value && isCompletedWeeklyUpdateError(error)) {
    weeklyDrawer.value = false
    await refreshActiveMode()
  }
} finally {
```

- [ ] **Step 4: 更新登记列表与详情入口**

将“本周进展”列改为三态：

```vue
<span v-if="row.currentWeekUpdated" class="updated-state">
  <el-icon><CircleCheck /></el-icon>已更新
</span>
<span v-else-if="isCompletedMatter(row)" class="updated-state">
  <el-icon><CircleCheck /></el-icon>无需更新
</span>
<span v-else class="pending-state">本周待更新</span>
```

在登记列表周报图标按钮外层的 `el-tooltip` 增加：

```vue
v-if="requiresWeeklyUpdate(row)"
```

在详情顶部周报按钮增加：

```vue
v-if="requiresWeeklyUpdate(selectedMatter)"
```

历史时间线编辑按钮不增加该限制，因为其传入的是已有周报。

- [ ] **Step 5: 更新普通周会状态**

将 `weekComparison` 的空记录分支改为：

```ts
if (!current) {
  return isCompletedMatter(matter)
    ? { label: '本周已完成，无需更新', tone: 'complete' }
    : { label: '本周待更新', tone: 'missing' }
}
```

周会卡片操作区改为：

```vue
<span v-if="!requiresWeeklyUpdate(matter)" class="completed-update-state">
  本周已完成，无需更新
</span>
<el-button
  v-else-if="!reportUpdate(matter)"
  type="warning"
  size="small"
  @click="openPresentation(presentationIndexOf(matter.id))"
>立即更新</el-button>
<el-button v-else link type="primary" :icon="Calendar" @click="openWeekly(matter)">
  更新周报
</el-button>
```

将缺失周报区域从单一 `v-else` 改为：

```vue
<div v-else-if="isCompletedMatter(matter)" class="completed-no-update" aria-label="已完成事项无需更新">
  <span class="missing-icon"><el-icon><CircleCheck /></el-icon></span>
  <strong>本周已完成，无需更新</strong>
  <small>事项已结束，不再要求补交周度更新</small>
</div>
<button v-else class="missing-update" type="button" @click="openPresentation(presentationIndexOf(matter.id))">
  <span class="missing-icon"><el-icon><EditPen /></el-icon></span>
  <strong>本周待更新</strong>
  <small>请及时填写本周进展、风险及下一步计划</small>
</button>
```

- [ ] **Step 6: 更新演示模式为只读完成态**

增加计算属性：

```ts
const presentationRequiresUpdate = computed(() =>
  presentationMatter.value ? requiresWeeklyUpdate(presentationMatter.value) : false)
```

将 `hydratePresentationForm` 的编辑状态改为：

```ts
presentationEditing.value = requiresWeeklyUpdate(matter)
  && (forceEdit || !reportUpdate(matter) || presentationDrafts.has(matter.id))
```

`startPresentationEdit` 和 `savePresentationAndNext` 首行增加完成保护：

```ts
if (!presentationMatter.value || !requiresWeeklyUpdate(presentationMatter.value)) return
```

演示卡片的 pending class 改为：

```vue
:class="{ 'is-pending': presentationRequiresUpdate && (!presentationUpdate || presentationEditing) }"
```

头部状态文案使用三态：

```vue
<span v-if="!presentationRequiresUpdate" class="updated-chip">
  <el-icon><Select /></el-icon>无需更新
</span>
<span v-else-if="presentationUpdate && !presentationEditing" class="updated-chip">
  <el-icon><Select /></el-icon>已更新
</span>
<span v-else class="presentation-pending-label">本周待更新</span>
```

在现有周报只读区与编辑区之间增加：

```vue
<div v-else-if="!presentationRequiresUpdate" class="presentation-complete-view" aria-label="已完成事项无需更新">
  <el-icon><CircleCheck /></el-icon>
  <strong>本周已完成，无需更新</strong>
  <p>事项已结束，后续周次不再要求提交周度更新。</p>
</div>
```

将 footer 三个分支完整调整为完成态、已有周报、待填写三态；完成态只保留详情按钮：

```vue
<div v-if="!presentationRequiresUpdate" class="presentation-actions">
  <el-button type="primary" @click="openDetail(presentationMatter)">
    查看详情 <el-icon><Right /></el-icon>
  </el-button>
</div>
<div v-else-if="presentationUpdate && !presentationEditing" class="presentation-actions">
  <el-button @click="startPresentationEdit"><el-icon><EditPen /></el-icon>编辑周报</el-button>
  <el-button type="primary" @click="openDetail(presentationMatter)">
    查看详情 <el-icon><Right /></el-icon>
  </el-button>
</div>
<div v-else class="presentation-actions">
  <el-button @click="stashPresentationDraft"><el-icon><Collection /></el-icon>暂存草稿</el-button>
  <el-button type="warning" :loading="presentationSaving" @click="savePresentationAndNext">
    <el-icon><Select /></el-icon>保存并下一项 <el-icon><Right /></el-icon>
  </el-button>
</div>
```

在 `savePresentationAndNext` catch 中，对完成错误刷新周会数据：

```ts
} catch (error: unknown) {
  ElMessage.error(errorMessage(error, '周报保存失败'))
  if (isCompletedWeeklyUpdateError(error)) {
    presentationDrafts.delete(matter.id)
    await loadMeeting()
    hydratePresentationForm()
  }
} finally {
```

- [ ] **Step 7: 增加完成态样式**

在现有 `.missing-update` 和演示卡片样式附近增加：

```css
.completed-update-state {
  color: var(--success);
  font-size: 12px;
  font-weight: 600;
}

.completed-no-update,
.presentation-complete-view {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 8px;
  min-height: 132px;
  border: 1px solid #bbf7d0;
  border-radius: 10px;
  background: #f0fdf4;
  color: #166534;
  text-align: center;
}

.completed-no-update small,
.presentation-complete-view p {
  margin: 0;
  color: #4b7b63;
  font-size: 12px;
}

.presentation-complete-view .el-icon {
  font-size: 32px;
}

.week-delta.tone-complete {
  color: var(--success);
  background: #f0fdf4;
}
```

- [ ] **Step 8: 运行前端定向测试和构建**

Run:

```bash
cd frontend
npx playwright test tests/key-matters.spec.ts \
  --grep "已完成事项不再要求|填写期间事项被完成"
npm run build
```

Expected: 两个新增 Playwright 用例 PASS，`vue-tsc` 和 Vite build 成功。

- [ ] **Step 9: 提交前端实现**

```bash
git add frontend/src/views/KeyMattersView.vue
git commit -m "fix(key-matters): hide updates for completed matters"
```

### Task 4: 契约同步与全量回归

**Files:**
- Modify: `.trellis/spec/backend/key-matter-management-contract.md`
- Modify: `.trellis/spec/frontend/key-matter-management-ui.md`

- [ ] **Step 1: 更新后端可执行契约**

在后端契约的 Contracts 和 Validation Matrix 中加入：

```markdown
Completed matters do not require new weekly updates. Weekly upsert locks the
matter row before deciding whether the target week is an insert or update.
A completed matter rejects a missing target week with
`已完成事项无需新增周进展`; an existing weekly row remains editable and never
synchronizes the completed matter back to a non-completed state.
```

错误矩阵增加：

```markdown
| Completed matter + target week missing | validation error `已完成事项无需新增周进展`; no row inserted |
| Completed matter + target week exists | update weekly row only; matter status/progress/completedAt unchanged |
```

- [ ] **Step 2: 更新前端 UI 契约**

在前端契约 Page Contract 和 Test Points 中加入：

```markdown
- Completed matters never show a create-weekly-update action and do not count as
  missing. Their existing weekly history remains editable from detail.
- A matter completed during the selected meeting week remains visible as a
  read-only briefing. Without a weekly row it displays `本周已完成，无需更新`
  instead of the inline editor.
- A create-weekly-update request rejected because the matter completed
  concurrently closes the create form, refreshes data, and preserves the server
  error message.
```

- [ ] **Step 3: 运行完整后端测试**

Run:

```bash
cd backend
mvn test
```

Expected: `BUILD SUCCESS`，无失败和错误测试。

- [ ] **Step 4: 运行大事儿完整 E2E 和前端构建**

Run:

```bash
cd frontend
npx playwright test tests/key-matters.spec.ts
npm run build
```

Expected: `key-matters.spec.ts` 全部 PASS，构建成功；仅允许现有 chunk-size warning。

- [ ] **Step 5: 提交契约和最终测试修正**

```bash
git add .trellis/spec/backend/key-matter-management-contract.md \
  .trellis/spec/frontend/key-matter-management-ui.md
git commit -m "docs(key-matters): record completed update contract"
```

### Task 5: 生产发布、冒烟与回滚记录

**Files:**
- Build output: `backend/target/management-1.0.0.jar`
- Build output: `frontend/dist/`
- Remote marker: `/home/openclaw/superwork-claude-sp/DEPLOYED_COMMIT`

- [ ] **Step 1: 生成最终发布产物**

```bash
cd backend
mvn clean package
cd ../frontend
npm run build
cd ..
```

Expected: 后端 `BUILD SUCCESS`，前端 `built` 成功。

- [ ] **Step 2: 创建生产备份**

```bash
TS=$(date +%Y%m%d-%H%M%S)
ssh 241 "mkdir -p /home/openclaw/deploy-backups/superwork-key-matter-completed-$TS && \
  cp /home/openclaw/superwork-claude-sp/backend/target/management-1.0.0.jar \
     /home/openclaw/deploy-backups/superwork-key-matter-completed-$TS/ && \
  cp -r /home/openclaw/superwork-claude-sp/frontend/dist \
     /home/openclaw/deploy-backups/superwork-key-matter-completed-$TS/frontend-dist"
```

Expected: 远端备份目录同时包含旧 jar 和旧前端 dist。

- [ ] **Step 3: 同步产物和本次源文件**

```bash
rsync -az backend/target/management-1.0.0.jar \
  241:/home/openclaw/superwork-claude-sp/backend/target/management-1.0.0.jar
rsync -az --delete frontend/dist/ \
  241:/home/openclaw/superwork-claude-sp/frontend/dist/
rsync -az backend/src/main/java/com/bu/management/mapper/BuKeyMatterMapper.java \
  241:/home/openclaw/superwork-claude-sp/backend/src/main/java/com/bu/management/mapper/BuKeyMatterMapper.java
rsync -az backend/src/main/java/com/bu/management/service/BuKeyMatterService.java \
  241:/home/openclaw/superwork-claude-sp/backend/src/main/java/com/bu/management/service/BuKeyMatterService.java
rsync -az backend/src/test/java/com/bu/management/service/BuKeyMatterServiceTest.java \
  241:/home/openclaw/superwork-claude-sp/backend/src/test/java/com/bu/management/service/BuKeyMatterServiceTest.java
rsync -az frontend/src/views/KeyMattersView.vue \
  241:/home/openclaw/superwork-claude-sp/frontend/src/views/KeyMattersView.vue
rsync -az frontend/tests/key-matters.spec.ts \
  241:/home/openclaw/superwork-claude-sp/frontend/tests/key-matters.spec.ts
```

Expected: jar、dist 和本次修改的源文件均同步到精确路径，不覆盖其他模块。

- [ ] **Step 4: 重建并启动前后端容器**

```bash
ssh 241 'cd /home/openclaw/superwork-claude-sp/docker && \
  docker compose -f docker-compose.241.yml build backend frontend && \
  docker compose -f docker-compose.241.yml up -d backend frontend'
```

Expected: `superwork-bu-backend` 和 `superwork-bu-frontend` 均为 `Up`。

- [ ] **Step 5: 验证健康、登录和页面资源**

```bash
ssh 241 'curl -fsS http://localhost:18081/actuator/health && \
  curl -fsS http://localhost:18080/ | grep -q "<div id=\"app\"></div>"'

cd frontend
PLAYWRIGHT_BASE_URL=http://192.168.1.241:18080 \
  npx playwright test tests/key-matters.spec.ts --grep "已完成事项不再要求"
cd ..
```

Expected: health 为 `UP`，前端首页资源存在，生产 URL 的完成事项用例 PASS。

- [ ] **Step 6: 无写入验证后端拦截**

登录并选择一个已完成事项，使用未来周一确保目标周不存在；请求必须被拒绝且不能产生数据：

```bash
ssh 241 '
TOKEN=$(curl -fsS -X POST http://localhost:18080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"123456\"}" | jq -r .data.accessToken)
MATTER_ID=$(curl -fsS http://localhost:18080/api/key-matters \
  -H "Authorization: Bearer $TOKEN" | jq -r ".data[] | select(.status == \"已完成\") | .id" | head -1)
FUTURE_WEEK=$(date -d "next monday + 14 days" +%F)
test -n "$MATTER_ID"
RESPONSE=$(curl -sS -X PUT \
  "http://localhost:18080/api/key-matters/$MATTER_ID/weekly-updates/$FUTURE_WEEK" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"status\":\"已完成\",\"progress\":100,\"progressSummary\":\"发布冒烟，不应写入\"}")
echo "$RESPONSE" | jq -e ".message == \"已完成事项无需新增周进展\""
'
```

Expected: `jq` 返回 true；由于请求被后端拒绝，不产生未来周报。

- [ ] **Step 7: 更新部署标记并记录回滚命令**

```bash
COMMIT=$(git rev-parse --short HEAD)
ssh 241 "printf '$COMMIT' > /home/openclaw/superwork-claude-sp/DEPLOYED_COMMIT"
```

回滚命令保存在发布记录中：恢复 Step 2 的 jar/dist，然后执行：

```bash
ssh 241 'cd /home/openclaw/superwork-claude-sp/docker && \
  docker compose -f docker-compose.241.yml build backend frontend && \
  docker compose -f docker-compose.241.yml up -d backend frontend'
```

Expected: `DEPLOYED_COMMIT` 等于本次提交短哈希，发布记录包含备份目录、健康结果、E2E 结果和回滚命令。
