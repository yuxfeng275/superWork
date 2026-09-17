import { test, expect, type Route } from '@playwright/test'

const fulfill = (route: Route, data: unknown) => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, message: 'success', data })
})

const analysis = {
  statusDistribution: [
    { key: 'IN_PROGRESS', label: '进行中', count: 172, percentage: 67.2 },
    { key: 'PENDING', label: '待处理', count: 84, percentage: 32.8 }
  ],
  projectDistribution: [
    { key: '11', label: '电商经营中台', count: 96, percentage: 37.5 },
    { key: '12', label: '会员增长与营销平台', count: 82, percentage: 32 }
  ],
  ownerDistribution: [
    { key: '1', label: '张群成', count: 58, percentage: 22.7 },
    { key: '2', label: '小刘洋', count: 46, percentage: 18 }
  ],
  sourceDistribution: [{ key: 'YUNXIAO', label: '云效', count: 256, percentage: 100 }],
  priorityDistribution: [],
  overdueProjectDistribution: [
    { key: '11', label: '会员增长与营销平台', count: 68, percentage: 39.5 },
    { key: '12', label: '电商经营中台', count: 54, percentage: 31.4 },
    { key: '13', label: '客户体验升级专项', count: 29, percentage: 16.9 }
  ],
  overdueOwnerDistribution: [
    { key: '1', label: '张群成', count: 38, percentage: 22.1 },
    { key: '2', label: '小刘洋', count: 31, percentage: 18 },
    { key: '3', label: '田蜜', count: 24, percentage: 14 }
  ],
  overdueAgeDistribution: [
    { key: 'D1_7', label: '逾期 1-7 天', count: 21, percentage: 12.2 },
    { key: 'D8_30', label: '逾期 8-30 天', count: 47, percentage: 27.3 },
    { key: 'D31_90', label: '逾期 31-90 天', count: 61, percentage: 35.5 },
    { key: 'D90_PLUS', label: '逾期 90 天以上', count: 43, percentage: 25 }
  ],
  totalEstimatedHours: 68840,
  totalActualHours: 17058.2,
  completionRate: 65.5,
  unassignedCount: 12,
  overdueIncompleteCount: 172,
  missingDueDateCount: 2656
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'visual-token')
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' }))
  })
  await page.route('**/api/**', route => fulfill(route, []))
  await page.route('**/api/auth/me', route => fulfill(route, { id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR', permissions: ['task:list'] }))
  await page.route('**/api/projects/tree', route => fulfill(route, []))
  await page.route('**/api/users**', route => fulfill(route, { records: [] }))
  await page.route('**/api/tasks/overview**', route => fulfill(route, {
    summary: { totalCount: 256, pendingCount: 84, inProgressCount: 172, completedCount: 0, testedCount: 0, unassignedCount: 12, totalEstimatedHours: 68840, totalActualHours: 17058.2, statusCounts: { 开发中: 172, 待开始: 84 } },
    analysis,
    tasks: [{ recordKey: 'yunxiao:1', dataSource: 'YUNXIAO', readOnly: true, title: '会员权益中心重构与历史数据迁移', status: '开发中', normalizedStatus: 'IN_PROGRESS', requirementNo: 'TASK-2108', requirementTitle: '会员增长平台升级', assigneeName: '张群成', projectName: '会员增长与营销平台', estimatedHours: 32, createdAt: '2026-06-01T09:00:00', dueDate: '2026-07-01', overdueIncomplete: true, overdueDays: 43 }]
  }))
})

test('风险看板桌面布局截图', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/tasks')
  await page.getByRole('tab', { name: '执行分析' }).click()
  await expect(page.locator('.risk-dashboard')).toBeVisible()
  await page.screenshot({ path: 'test-results/workitem-risk-desktop.png', fullPage: true })
})

test('风险看板移动布局截图', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/tasks')
  await page.getByRole('tab', { name: '执行分析' }).click()
  await expect(page.locator('.risk-dashboard')).toBeVisible()
  await page.screenshot({ path: 'test-results/workitem-risk-mobile.png', fullPage: true })
  await page.locator('.risk-dashboard').screenshot({ path: 'test-results/workitem-risk-mobile-panel.png' })
})
