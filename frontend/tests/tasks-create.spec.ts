import { expect, test, type Route } from '@playwright/test'

const mockBusinessLines = [
  { id: 1, name: '全渠道云鹿定制', status: 1 }
]

const mockProjects = [
  {
    id: 11,
    parentId: null,
    businessLineId: 1,
    name: '皇家项目',
    fullPath: '皇家项目',
    children: [
      { id: 12, parentId: 11, businessLineId: 1, name: 'PMS', fullPath: '皇家项目/PMS', children: [] }
    ]
  }
]

const mockUsers = [
  { id: 2, username: 'dev_zhao', realName: '赵全栈', role: 'FULL_STACK_ENGINEER', status: 1 }
]

const mockProjectMembers = [
  { id: 201, projectId: 12, userId: 2, username: 'dev_zhao', realName: '赵全栈', role: '全栈工程师' }
]

const mockRequirements = [
  {
    id: 101,
    reqNo: 'REQ-2026-0001',
    title: '用户管理优化',
    projectId: 12,
    projectName: 'PMS',
    projectFullPath: '皇家项目/PMS'
  }
]

const mockOverview = {
  summary: {
    totalCount: 1,
    pendingCount: 1,
    inProgressCount: 0,
    completedCount: 0,
    testedCount: 0,
    unassignedCount: 0,
    totalEstimatedHours: 6,
    totalActualHours: 0,
    statusCounts: { 待开始: 1 }
  },
  tasks: [
    {
      id: 1,
      requirementId: 101,
      requirementNo: 'REQ-2026-0001',
      requirementTitle: '用户管理优化',
      projectId: 12,
      projectName: 'PMS',
      projectFullPath: '皇家项目/PMS',
      assigneeId: 2,
      assigneeName: '赵全栈',
      title: '已有任务',
      taskType: '开发任务',
      estimatedHours: 6,
      status: '待开始'
    }
  ]
}

const mockTaskDetail = {
  id: 1,
  requirementId: 101,
  title: '已有任务',
  description: '检查用户管理页面的保存链路',
  taskType: '开发任务',
  assigneeId: 2,
  estimatedHours: 6,
  actualHours: 2,
  status: '待开始',
  updatedAt: '2026-07-17T09:30:00'
}

const fulfillJson = async (route: Route, body: unknown) => {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(body)
  })
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem(
      'user',
      JSON.stringify({ id: 999, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' })
    )
  })

  await page.route('**/api/projects/tree**', async route => {
    await fulfillJson(route, { code: 200, message: 'success', data: mockProjects, timestamp: new Date().toISOString() })
  })

  await page.route('**/api/business-lines**', async route => {
    await fulfillJson(route, { code: 200, message: 'success', data: { records: mockBusinessLines }, timestamp: new Date().toISOString() })
  })

  await page.route('**/api/users**', async route => {
    await fulfillJson(route, { code: 200, message: 'success', data: { records: mockUsers }, timestamp: new Date().toISOString() })
  })

  await page.route('**/api/project-members/by-project**', async route => {
    await fulfillJson(route, { code: 200, message: 'success', data: mockProjectMembers, timestamp: new Date().toISOString() })
  })

  await page.route('**/api/requirements**', async route => {
    await fulfillJson(route, { code: 200, message: 'success', data: { records: mockRequirements }, timestamp: new Date().toISOString() })
  })
})

test('任务管理页可以新增任务并刷新概览', async ({ page }) => {
  let createdTaskPayload: Record<string, unknown> | null = null
  let overviewCalls = 0

  await page.route('**/api/tasks**', async route => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname

    if (pathname === '/api/tasks/overview') {
      overviewCalls += 1
      await fulfillJson(route, mockOverview)
      return
    }

    if (pathname === '/api/tasks' && request.method() === 'POST') {
      createdTaskPayload = JSON.parse(request.postData() || '{}')
      await fulfillJson(route, { id: 88, ...createdTaskPayload })
      return
    }

    await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/tasks')

  await expect(page.getByRole('button', { name: '新增任务' })).toBeVisible()
  await page.getByRole('button', { name: '新增任务' }).click()

  const dialog = page.locator('.el-dialog').filter({ hasText: '新增任务' })
  await expect(dialog).toBeVisible()

  await dialog.locator('.business-tab-modal', { hasText: '全渠道云鹿定制' }).click()
  const projectCard = dialog.locator('.project-card-modal', { hasText: '皇家项目' })
  await projectCard.click()
  await expect(projectCard).toHaveClass(/selected/)
  await dialog.locator('.sub-tag-modal', { hasText: 'PMS' }).click()
  await expect(dialog.locator('.sub-tag-modal', { hasText: 'PMS' })).toHaveClass(/selected/)

  await dialog.locator('.el-form-item').filter({ hasText: '关联需求' }).locator('.el-select').click()
  await page.getByRole('option', { name: /REQ-2026-0001/ }).click()

  await dialog.getByPlaceholder('例如：联调订单接口').fill('订单接口联调')

  await dialog.locator('.el-form-item').filter({ hasText: '负责人' }).locator('.el-select').click()
  await page.getByRole('option', { name: '赵全栈' }).click()

  await dialog.locator('.el-form-item').filter({ hasText: '任务类型' }).locator('.el-select').click()
  await page.getByRole('option', { name: '后端开发' }).click()

  await dialog.locator('.el-form-item').filter({ hasText: '预估工时' }).locator('input').fill('8')
  await dialog.getByPlaceholder('补充任务范围、交付物或注意事项').fill('完成接口联调并输出自测结果')

  await dialog.getByRole('button', { name: '创建' }).click()

  await expect(page.locator('.el-message--success')).toBeVisible()
  expect(createdTaskPayload).toMatchObject({
    requirementId: 101,
    title: '订单接口联调',
    assigneeId: 2,
    taskType: '后端开发',
    estimatedHours: 8,
    description: '完成接口联调并输出自测结果',
    createdBy: 999
  })
  expect(overviewCalls).toBeGreaterThanOrEqual(2)
})

test('任务管理页可以打开任务详情并修改状态', async ({ page }) => {
  let overviewCalls = 0
  let detailCalls = 0
  let updatedTaskPayload: Record<string, unknown> | null = null
  let currentStatus = '待开始'

  await page.route('**/api/tasks**', async route => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname

    if (pathname === '/api/tasks/overview') {
      overviewCalls += 1
      await fulfillJson(route, {
        ...mockOverview,
        summary: {
          ...mockOverview.summary,
          pendingCount: currentStatus === '待开始' ? 1 : 0,
          inProgressCount: currentStatus === '进行中' ? 1 : 0,
          statusCounts: {
            待开始: currentStatus === '待开始' ? 1 : 0,
            进行中: currentStatus === '进行中' ? 1 : 0
          }
        },
        tasks: mockOverview.tasks.map(task => ({ ...task, status: currentStatus }))
      })
      return
    }

    if (pathname === '/api/tasks/1' && request.method() === 'GET') {
      detailCalls += 1
      await fulfillJson(route, { ...mockTaskDetail, status: currentStatus })
      return
    }

    if (pathname === '/api/tasks/1' && request.method() === 'PUT') {
      updatedTaskPayload = JSON.parse(request.postData() || '{}')
      currentStatus = String(updatedTaskPayload.status || currentStatus)
      await fulfillJson(route, { ...mockTaskDetail, status: currentStatus })
      return
    }

    await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/tasks')

  const taskRow = page.locator('.task-row', { hasText: '已有任务' })
  await expect(taskRow).toBeVisible()
  await taskRow.locator('.task-title').click()

  const drawer = page.locator('.el-drawer').filter({ hasText: '已有任务' })
  await expect(drawer).toBeVisible()
  await expect(drawer).toContainText('检查用户管理页面的保存链路')
  expect(detailCalls).toBe(1)

  await drawer.locator('.task-status-select.detail').selectOption('进行中')

  await expect(page.locator('.el-message--success')).toBeVisible()
  await expect(drawer.locator('.task-status-select.detail')).toHaveValue('进行中')
  expect(updatedTaskPayload).toMatchObject({ status: '进行中' })
  expect(overviewCalls).toBeGreaterThanOrEqual(2)
})
