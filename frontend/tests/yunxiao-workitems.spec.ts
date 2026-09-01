import { expect, test, type Route } from '@playwright/test'

const fulfill = (route: Route, data: unknown) => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, message: 'success', data, timestamp: new Date().toISOString() })
})

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify({
      id: 1,
      username: 'admin',
      realName: '管理员',
      role: 'DIRECTOR'
    }))
  })

  await page.route('**/api/projects/tree**', route => fulfill(route, []))
  await page.route('**/api/projects**', route => fulfill(route, { records: [], total: 0 }))
  await page.route('**/api/business-lines**', route => fulfill(route, { records: [], total: 0 }))
  await page.route('**/api/users**', route => fulfill(route, { records: [], total: 0 }))
  await page.route('**/api/requirements?**', route => fulfill(route, { records: [], total: 0 }))
})

test('需求管理合并已绑定需求并独立展示未绑定云效需求', async ({ page }) => {
  await page.route('**/api/requirements/overview**', route => fulfill(route, {
    records: [
      {
        recordKey: 'local:1',
        dataSource: 'LOCAL',
        readOnly: false,
        id: 1,
        requirementId: 1,
        requirementNo: 'REQ-1',
        title: '客户数据驾驶舱',
        projectId: 11,
        projectIds: [11],
        projectNames: ['经营中台'],
        status: '开发中',
        normalizedStatus: 'IN_PROGRESS',
        priority: '高',
        linkedYunxiaoWorkitemId: 'cloud-1',
        linkedYunxiaoSerialNumber: 'YX-101',
        linkedYunxiaoStatus: '开发中',
        createdAt: '2026-08-01T09:00:00',
        dueDate: '2026-08-08',
        overdueIncomplete: true,
        overdueDays: 3
      },
      {
        recordKey: 'yunxiao:cloud-2',
        dataSource: 'YUNXIAO',
        readOnly: true,
        yunxiaoWorkitemId: 'cloud-2',
        serialNumber: 'YX-102',
        title: '移动端审批优化',
        projectIds: [11],
        projectNames: ['经营中台'],
        assigneeName: '李敏',
        status: '待评审',
        normalizedStatus: 'PENDING',
        description: '云效中的独立需求。'
      }
    ],
    total: 2,
    current: 1,
    size: 200,
    summary: { totalCount: 2, localCount: 1, yunxiaoCount: 1, pendingCount: 1, inProgressCount: 1, completedCount: 0, otherCount: 0 },
    analysis: {
      statusDistribution: [{ key: 'IN_PROGRESS', label: '进行中', count: 1, percentage: 50 }, { key: 'PENDING', label: '待处理', count: 1, percentage: 50 }],
      projectDistribution: [{ key: '11', label: '经营中台', count: 2, percentage: 100 }],
      ownerDistribution: [{ key: 'unassigned:李敏', label: '李敏', count: 1, percentage: 50 }],
      sourceDistribution: [{ key: 'LOCAL', label: '本地', count: 1, percentage: 50 }, { key: 'YUNXIAO', label: '云效', count: 1, percentage: 50 }],
      priorityDistribution: [{ key: '高', label: '高', count: 1, percentage: 50 }],
      overdueProjectDistribution: [{ key: '11', label: '经营中台', count: 1, percentage: 100 }],
      overdueOwnerDistribution: [{ key: 'unassigned:未分配', label: '未分配', count: 1, percentage: 100 }],
      overdueAgeDistribution: [{ key: 'D1_7', label: '逾期 1-7 天', count: 1, percentage: 100 }],
      completionRate: 0,
      unassignedCount: 1,
      overdueIncompleteCount: 1,
      missingDueDateCount: 1,
      totalEstimatedHours: 0,
      totalActualHours: 0
    }
  }))

  await page.goto('/requirements')

  await expect(page.getByText('客户数据驾驶舱')).toHaveCount(1)
  await expect(page.getByText('已关联 YX-101')).toBeVisible()
  await expect(page.getByText('移动端审批优化')).toBeVisible()
  await expect(page.locator('.source-badge.yunxiao')).toBeVisible()
  await page.getByRole('tab', { name: '结构分析' }).click()
  await expect(page.getByRole('heading', { name: '需求结构分析' })).toBeVisible()
  await expect(page.getByText('优先级分布')).toBeVisible()
  await expect(page.locator('.risk-dashboard')).toBeVisible()
  await expect(page.locator('.risk-dashboard').getByText('风险项目')).toBeVisible()
  await expect(page.locator('.analysis-grid').getByText('超期项目分布')).toHaveCount(0)

  await page.getByRole('tab', { name: '需求明细' }).click()
  await expect(page.locator('.overdue-pill').getByText('超期 3 天')).toBeVisible()
  await page.getByRole('button', { name: '云效', exact: true }).click()
  await expect(page.getByText('客户数据驾驶舱')).toHaveCount(0)
  await expect(page.getByText('移动端审批优化')).toBeVisible()
})

test('任务管理展示云效任务且不提供本地状态编辑', async ({ page }) => {
  await page.route('**/api/tasks/overview**', route => fulfill(route, {
    summary: {
      totalCount: 1,
      pendingCount: 0,
      inProgressCount: 1,
      completedCount: 0,
      testedCount: 0,
      unassignedCount: 0,
      totalEstimatedHours: 8,
      totalActualHours: 3,
      statusCounts: { 开发中: 1 }
    },
    analysis: {
      statusDistribution: [{ key: 'IN_PROGRESS', label: '进行中', count: 1, percentage: 100 }],
      projectDistribution: [{ key: '11', label: '经营中台', count: 1, percentage: 100 }],
      ownerDistribution: [{ key: 'unassigned:张凯', label: '张凯', count: 1, percentage: 100 }],
      sourceDistribution: [{ key: 'YUNXIAO', label: '云效', count: 1, percentage: 100 }],
      priorityDistribution: [],
      overdueProjectDistribution: [{ key: '11', label: '经营中台', count: 1, percentage: 100 }],
      overdueOwnerDistribution: [{ key: 'unassigned:张凯', label: '张凯', count: 1, percentage: 100 }],
      overdueAgeDistribution: [{ key: 'D8_30', label: '逾期 8-30 天', count: 1, percentage: 100 }],
      completionRate: 0,
      unassignedCount: 0,
      overdueIncompleteCount: 1,
      missingDueDateCount: 0,
      totalEstimatedHours: 8,
      totalActualHours: 3
    },
    tasks: [{
      recordKey: 'yunxiao:task-21',
      dataSource: 'YUNXIAO',
      readOnly: true,
      yunxiaoWorkitemId: 'task-21',
      serialNumber: 'TASK-21',
      title: '审批节点兼容处理',
      projectId: 11,
      projectName: '经营中台',
      projectIds: [11],
      projectNames: ['经营中台'],
      assigneeName: '张凯',
      status: '开发中',
      normalizedStatus: 'IN_PROGRESS',
      estimatedHours: 8,
      actualHours: 3,
      description: '兼容旧版审批节点。',
      createdAt: '2026-07-20T09:20:00',
      dueDate: '2026-08-01',
      overdueIncomplete: true,
      overdueDays: 10,
      updatedAt: '2026-08-11T09:20:00'
    }]
  }))

  await page.goto('/tasks')

  await expect(page.getByText('审批节点兼容处理')).toBeVisible()
  await expect(page.locator('.source-badge.yunxiao')).toBeVisible()
  await page.getByRole('tab', { name: '执行分析' }).click()
  await expect(page.getByRole('heading', { name: '任务执行分析' })).toBeVisible()
  await expect(page.getByText('工时执行率')).toBeVisible()
  await expect(page.getByText('37.5%')).toBeVisible()
  await expect(page.locator('.risk-dashboard')).toBeVisible()
  await expect(page.locator('.risk-dashboard').getByText('逾期时长')).toBeVisible()
  await expect(page.locator('.analysis-grid').getByText('逾期时长分布')).toHaveCount(0)
  await expect(page.getByLabel('修改任务状态：审批节点兼容处理')).toHaveCount(0)

  await page.getByRole('tab', { name: '任务明细' }).click()
  await expect(page.locator('.overdue-pill').getByText('超期 10 天')).toBeVisible()
  await page.getByText('审批节点兼容处理').click()
  await expect(page.getByText('只读数据，以云效为准')).toBeVisible()
  await expect(page.getByText('兼容旧版审批节点。')).toBeVisible()
})

test('缺陷管理独立展示云效缺陷并提供只读详情', async ({ page }) => {
  await page.route('**/api/defects/overview**', route => fulfill(route, {
    records: [
      {
        recordKey: 'yunxiao:bug-318',
        dataSource: 'YUNXIAO',
        readOnly: true,
        yunxiaoWorkitemId: 'bug-318',
        serialNumber: 'BUG-318',
        category: 'Bug',
        title: '审批流退回后状态未刷新',
        description: '退回后重新进入详情，状态仍显示审批中。',
        projectIds: [11],
        projectNames: ['经营中台'],
        assigneeName: '张凯',
        status: '修复中',
        normalizedStatus: 'IN_PROGRESS',
        estimatedHours: 6,
        actualHours: 4,
        createdAt: '2026-06-01T09:00:00',
        dueDate: '2026-07-01',
        overdueIncomplete: true,
        overdueDays: 41,
        updatedAt: '2026-08-11T09:42:00',
        lastSyncedAt: '2026-08-11T10:15:00'
      }
    ],
    total: 1,
    current: 1,
    size: 20,
    summary: {
      totalCount: 1,
      localCount: 0,
      yunxiaoCount: 1,
      pendingCount: 0,
      inProgressCount: 1,
      completedCount: 0,
      otherCount: 0
    },
    analysis: {
      statusDistribution: [{ key: 'IN_PROGRESS', label: '进行中', count: 1, percentage: 100 }],
      projectDistribution: [{ key: '11', label: '经营中台', count: 1, percentage: 100 }],
      ownerDistribution: [{ key: 'unassigned:张凯', label: '张凯', count: 1, percentage: 100 }],
      sourceDistribution: [{ key: 'YUNXIAO', label: '云效', count: 1, percentage: 100 }],
      priorityDistribution: [],
      overdueProjectDistribution: [{ key: '11', label: '经营中台', count: 1, percentage: 100 }],
      overdueOwnerDistribution: [{ key: 'unassigned:张凯', label: '张凯', count: 1, percentage: 100 }],
      overdueAgeDistribution: [{ key: 'D31_90', label: '逾期 31-90 天', count: 1, percentage: 100 }],
      completionRate: 0,
      unassignedCount: 0,
      overdueIncompleteCount: 1,
      missingDueDateCount: 0,
      totalEstimatedHours: 6,
      totalActualHours: 4
    },
    lastSyncedAt: '2026-08-11T10:15:00'
  }))

  await page.goto('/defects')

  await expect(page.locator('.defects-page').getByRole('heading', { name: '缺陷管理' })).toBeVisible()
  await expect(page.getByText('云效只读')).toBeVisible()
  await expect(page.getByText('审批流退回后状态未刷新')).toBeVisible()
  await expect(page.getByText('BUG-318')).toBeVisible()
  await page.getByRole('tab', { name: '结构分析' }).click()
  await expect(page.getByRole('heading', { name: '缺陷结构分析' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '负责人分布', exact: true })).toBeVisible()
  await expect(page.locator('.risk-dashboard')).toBeVisible()
  await expect(page.locator('.risk-dashboard').getByText('逾期 31-90 天')).toBeVisible()
  await expect(page.locator('.analysis-grid').getByText('超期负责人分布')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /张凯.*1/ }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '新建缺陷' })).toHaveCount(0)

  await page.getByRole('tab', { name: '缺陷明细' }).click()
  await expect(page.locator('.overdue-pill').getByText('超期 41 天')).toBeVisible()
  await page.getByText('审批流退回后状态未刷新').click()
  await expect(page.getByRole('heading', { name: '审批流退回后状态未刷新' })).toBeVisible()
  await expect(page.getByText('退回后重新进入详情，状态仍显示审批中。')).toBeVisible()
  await expect(page.getByText('只读数据，以云效为准')).toBeVisible()
})
