# 大事儿个人快捷筛选设计

## 目标

在大事儿管理登记列表的现有“快速筛选”区域增加：

- `我的事项`：当前登录用户负责的事项。
- `我参与的事项`：当前登录用户作为参与人、但不是负责人的事项。

两个集合必须互斥，便于用户快速区分本人承担反馈责任的事项和仅参与协作的事项。本次只增加前端筛选，不改变后端查询、权限、参与关系或周进度反馈规则。

## 数据口径

### 当前用户

当前用户 ID 取自 `authStore.user?.id`。没有有效用户 ID 时，两个个人集合均为空。

### 我的事项

```ts
matter.ownerId === currentUserId
```

### 我参与的事项

```ts
matter.ownerId !== currentUserId
  && matter.participants?.some(participant => participant.userId === currentUserId)
```

负责人按系统不变量同时属于参与人，但必须从“我参与的事项”中排除，因此两个集合不重叠。

旧响应可能没有 `participants`。页面继续使用已有 `matterParticipants(matter)` 兼容逻辑合成负责人，但“我参与的事项”只依据响应中的实际参与人集合；缺少参与人字段时不能推断其他参与关系。

## 状态与派生数据

新增页面状态：

```ts
type PersonalScope = '' | 'owned' | 'participating'
const personalScope = ref<PersonalScope>('')
```

新增完整台账派生集合：

```ts
const ownedMatters = computed(() => allMatters.value.filter(isOwnedByCurrentUser))
const participatingMatters = computed(() => allMatters.value.filter(isParticipatedByCurrentUser))
```

个人筛选应用在服务端普通筛选结果 `matters` 之上：

```ts
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

分页、表格记录数和上下文空态均使用 `visibleMatters`。概览、项目/负责人分组和个人筛选数量仍使用完整 `allMatters`，不受当前快捷筛选影响。

## 交互规则

### 快捷筛选顺序

左侧快速筛选区域顺序为：

1. 全部事项
2. 我的事项
3. 我参与的事项
4. 项目分组
5. 负责人分组

个人筛选按钮显示固定数量：

- `我的事项 N`
- `我参与的事项 N`

数量来自 `ownedMatters.length` 和 `participatingMatters.length`。

### 激活与切换

- 点击“我的事项”设置 `personalScope = 'owned'`。
- 点击“我参与的事项”设置 `personalScope = 'participating'`。
- 选中按钮使用现有快捷筛选 active 视觉样式。
- 每次切换个人筛选将当前页重置为第 1 页。
- 点击当前已激活的个人筛选保持激活，不使用反选语义；返回全部通过“全部事项”。

### 条件重置

选择个人快捷筛选时：

- 清空关键词、状态、优先级、项目和负责人筛选。
- 重新加载完整列表结果。
- 保持概览统计不变。

以下操作必须清空 `personalScope`：

- 点击“全部事项”。
- 点击任一项目快捷筛选。
- 点击任一负责人快捷筛选。
- 点击普通筛选区域“查询”。
- 点击普通筛选区域“重置”。

这保证页面不会存在不可见的个人筛选与普通条件组合。

## 页面展示

### 空态

个人筛选无记录时使用上下文空态：

- `owned`：`暂无我负责的事项`
- `participating`：`暂无我参与的事项`
- 默认：保留现有大事儿空态。

空态不显示创建提示，因为普通负责人/参与人可能没有事项管理权限。

### 分页

- `pagedMatters` 从 `visibleMatters` 切片。
- 总数文案与分页 `total` 使用 `visibleMatters.length`。
- 个人筛选切换、普通查询、重置、项目/负责人快捷筛选和每页数量变化均回到第 1 页。
- 数据刷新后继续使用当前个人筛选，并通过现有页码 clamp 防止空页。

### 响应式

- 个人筛选复用现有快捷筛选按钮布局，不增加新的横向工具条。
- 320px、390px 移动端不得产生页面级横向溢出。
- 数量文案允许换行或省略，但按钮高度和图标区域保持稳定。

## 权限与安全

- 管理员、负责人和参与人只要 `canAccess=true`，均看到两个个人快捷筛选。
- 个人筛选不扩大可访问数据，只在后端已经授权返回的全部事项中做前端筛选。
- 周进度操作仍由 `canFeedbackMatter(matter)` 控制。
- 事项管理操作仍由 `canManageAll` 控制。
- 无关系用户仍由动态 access 路由守卫拒绝。

## 实现范围

修改：

- `frontend/src/views/KeyMattersView.vue`
- `frontend/tests/key-matter-participant-access.spec.ts`
- `frontend/tests/key-matters.spec.ts`（仅现有分页/概览回归需要时）
- `.trellis/spec/frontend/key-matter-management-ui.md`

不修改：

- 后端 Controller、Service 或查询 API。
- 数据库和 Flyway。
- 参与人或负责人权限模型。
- 周进度写入契约。
- 其他业务页面。

## 验收与测试

### 集合口径

- 当前用户负责的事项只出现在“我的事项”。
- 当前用户参与但不负责的事项只出现在“我参与的事项”。
- 同一事项不能同时出现在两个集合。
- 未参与事项不出现在任一个人集合。
- 个人筛选数量基于完整台账且正确。

### 交互

- 切换两个个人筛选更新表格并重置分页。
- “全部事项”、项目分组、负责人分组会清空个人筛选。
- 普通查询和重置会清空个人筛选。
- 概览统计不随个人筛选变化。
- 数据刷新后保留当前个人筛选。

### 状态

- 无本人负责事项显示 `暂无我负责的事项`。
- 无本人参与事项显示 `暂无我参与的事项`。
- 个人筛选不改变写操作权限；负责人仍只能反馈本人负责事项，参与人保持只读。

### 响应式与回归

- 320px、390px 无页面级横向溢出。
- 现有大事儿分页、项目/负责人快捷筛选、周会、参与人权限和完成事项测试继续通过。
- 前端全量 Playwright 和生产构建通过。
