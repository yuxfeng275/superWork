import { expect, test, type Page } from '@playwright/test'

const buildRequirement = (overrides: Record<string, unknown> = {}) => ({
  id: 777,
  reqNo: 'REQ-REAL-777',
  title: '真实接口需求标题',
  description: '<p>这是从接口返回的需求描述。</p>',
  type: '项目需求',
  businessLineId: 1,
  projectId: 12,
  customerContactId: 101,
  status: '评估中',
  priority: '高',
  source: '客户',
  expectedOnlineDate: '2026-05-01',
  creatorId: 9,
  createdAt: '2026-04-10T00:00:00',
  updatedAt: '2026-04-10T00:00:00',
  ...overrides
})

const confirmAction = async (page: Page) => {
  const dialog = page.locator('.el-message-box')
  await expect(dialog).toBeVisible()
  await dialog.locator('.el-button--primary').click()
}

test.beforeEach(async ({ context }) => {
  let currentRequirement = buildRequirement()
  let secondRequirement = buildRequirement({ id: 778, reqNo: 'REQ-REAL-778', title: '另一条真实需求', status: '待评估' })
  let currentDesignLogs: any[] = []
  let currentEvaluation: any = {
    id: 501,
    requirementId: 777,
    isFeasible: 1,
    feasibilityDesc: '接口可复用',
    estimatedWorkload: 12,
    estimatedCost: 5000,
    evaluatorId: 9,
    evaluatedAt: '2026-04-10T10:00:00',
    decision: null,
    decisionAt: null,
    decisionBy: null
  }
  let currentConfirmation: any = null
  let currentDelivery: any = null
  let currentTasks: any[] = []

  const requirements = [
    currentRequirement,
    secondRequirement
  ]

  await context.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem(
      'user',
      JSON.stringify({ id: 1, username: 'admin', realName: '系统管理员', role: 'BU_ADMIN' })
    )
  })

  await context.route('**/api/requirements/777/stage-actions', async route => {
    const payload = JSON.parse(route.request().postData() || '{}')
    if (payload.action === 'start_eval') currentRequirement = { ...currentRequirement, status: '评估中' }
    if (payload.action === 'start_design') {
      currentRequirement = { ...currentRequirement, status: '设计中' }
      currentDesignLogs = currentDesignLogs.map(log => log.status === '待开始'
        ? { ...log, status: '进行中', startedAt: '2026-04-10T12:40:00' }
        : log)
    }
    if (payload.action === 'start_test') currentRequirement = { ...currentRequirement, status: '测试中' }
    if (payload.action === 'test_pass') currentRequirement = { ...currentRequirement, status: '待上线' }
    if (payload.action === 'test_fail') currentRequirement = { ...currentRequirement, status: '开发中' }
    if (payload.action === 'go_online') currentRequirement = { ...currentRequirement, status: '已上线' }
    if (payload.action === 'reject') currentRequirement = { ...currentRequirement, status: '已拒绝' }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentRequirement, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/requirements/778/stage-actions', async route => {
    const payload = JSON.parse(route.request().postData() || '{}')
    if (payload.action === 'start_eval') {
      secondRequirement = { ...secondRequirement, status: '评估中' }
      requirements[1] = secondRequirement
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: secondRequirement, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/requirements**', async route => {
    if (route.request().url().includes('/stage-actions')) {
      await route.fallback()
      return
    }

    if (route.request().url().includes('/api/requirements/777')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: currentRequirement, timestamp: new Date().toISOString() })
      })
      return
    }

    if (route.request().url().includes('/api/requirements/778')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: secondRequirement, timestamp: new Date().toISOString() })
      })
      return
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { total: 7, records: requirements }, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/requirement-evaluations/by-requirement/777', async route => {
    if (!currentEvaluation) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 404, message: 'not found', data: null, timestamp: new Date().toISOString() }) })
      return
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentEvaluation, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/requirement-evaluations', async route => {
    const payload = JSON.parse(route.request().postData() || '{}')
    currentEvaluation = {
      ...currentEvaluation,
      ...payload,
      id: currentEvaluation?.id || 501,
      evaluatorId: 9,
      evaluatedAt: '2026-04-10T11:00:00'
    }
    currentRequirement = { ...currentRequirement, status: '评估中' }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentEvaluation, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/bu-decisions', async route => {
    const payload = JSON.parse(route.request().postData() || '{}')
    currentEvaluation = {
      ...currentEvaluation,
      decision: payload.decision,
      decisionBy: 1,
      decisionAt: '2026-04-10T12:00:00'
    }

    if (payload.decision === '通过') {
      currentRequirement = { ...currentRequirement, status: '待设计' }
    }

    if (payload.decision === '拒绝') {
      currentRequirement = { ...currentRequirement, status: '已拒绝' }
    }

    if (payload.decision === '转产品需求') {
      currentRequirement = { ...currentRequirement, status: '待设计', type: '产品需求', projectId: null, customerContactId: null }
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentEvaluation, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/design-work-logs/requirement/777', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentDesignLogs, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/design-work-logs', async route => {
    if (route.request().method() !== 'POST') {
      await route.fallback()
      return
    }

    const payload = JSON.parse(route.request().postData() || '{}')
    const existingIndex = currentDesignLogs.findIndex(log => log.workType === payload.workType)
    const nextLog = {
      id: existingIndex >= 0 ? currentDesignLogs[existingIndex].id : currentDesignLogs.length + 1,
      requirementId: 777,
      workType: payload.workType,
      designerId: payload.designerId,
      estimatedHours: payload.estimatedHours,
      plannedCompletedAt: payload.plannedCompletedAt,
      status: payload.status || '待开始',
      createdAt: '2026-04-10T12:30:00',
      updatedAt: '2026-04-10T12:30:00'
    }
    if (existingIndex >= 0) {
      currentDesignLogs[existingIndex] = nextLog
    } else {
      currentDesignLogs.push(nextLog)
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: nextLog, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/design-work-logs/*', async route => {
    if (route.request().url().includes('/api/design-work-logs/requirement/')) {
      await route.fallback()
      return
    }

    const id = Number(route.request().url().split('/').pop())
    if (route.request().method() === 'DELETE') {
      currentDesignLogs = currentDesignLogs.filter(log => log.id !== id)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: null, timestamp: new Date().toISOString() })
      })
      return
    }

    if (route.request().method() !== 'PUT') {
      await route.fallback()
      return
    }

    const payload = JSON.parse(route.request().postData() || '{}')
    currentDesignLogs = currentDesignLogs.map(log => log.id === id ? { ...log, ...payload } : log)
    if (currentDesignLogs.length > 0 && currentDesignLogs.every(log => log.status === '已完成')) {
      currentRequirement = { ...currentRequirement, status: '待确认' }
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentDesignLogs.find(log => log.id === id), timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/requirement-confirmations', async route => {
    currentConfirmation = {
      id: 901,
      requirementId: 777,
      confirmationType: JSON.parse(route.request().postData() || '{}').confirmationType,
      confirmedBy: 1,
      confirmedAt: '2026-04-10T15:00:00'
    }
    currentRequirement = { ...currentRequirement, status: '开发中' }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentConfirmation, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/requirement-confirmations/777', async route => {
    if (!currentConfirmation) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 404, message: 'not found', data: null, timestamp: new Date().toISOString() }) })
      return
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentConfirmation, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/requirement-deliveries/777', async route => {
    if (route.request().method() === 'POST') {
      currentRequirement = { ...currentRequirement, status: '已交付' }
      currentDelivery = { id: 801, requirementId: 777, deliveredBy: 1, deliveredAt: '2026-04-10T16:00:00' }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: currentDelivery, timestamp: new Date().toISOString() })
      })
      return
    }

    if (!currentDelivery) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 404, message: 'not found', data: null, timestamp: new Date().toISOString() }) })
      return
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentDelivery, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/requirement-deliveries/777/accept', async route => {
    currentRequirement = { ...currentRequirement, status: '已验收' }
    currentDelivery = { ...currentDelivery, acceptedBy: 1, acceptedAt: '2026-04-10T17:00:00' }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentDelivery, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/tasks/requirement/777', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentTasks, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/tasks', async route => {
    if (route.request().method() !== 'POST') {
      await route.fallback()
      return
    }

    const payload = JSON.parse(route.request().postData() || '{}')
    currentTasks = [
      ...currentTasks,
      {
        id: currentTasks.length + 1,
        requirementId: 777,
        title: payload.title,
        assigneeId: payload.assigneeId || 1,
        estimatedHours: payload.estimatedHours || 0,
        status: '待开始',
        createdAt: '2026-04-10T15:00:00'
      }
    ]

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentTasks.at(-1), timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/tasks/*', async route => {
    const taskId = Number(route.request().url().split('/').pop())
    const payload = JSON.parse(route.request().postData() || '{}')
    currentTasks = currentTasks.map(task => task.id === taskId ? { ...task, ...payload } : task)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: currentTasks.find(task => task.id === taskId), timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/business-lines**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: [{ id: 1, name: '全渠道云鹿定制' }] }, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/projects**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: {
          records: [
            { id: 11, businessLineId: 1, parentId: null, name: '皇家项目' },
            { id: 12, businessLineId: 1, parentId: 11, name: 'PMS' }
          ]
        },
        timestamp: new Date().toISOString()
      })
    })
  })

  await context.route('**/api/users**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: [{ id: 1, username: 'admin', realName: '系统管理员' }, { id: 9, username: 'pm', realName: '张项目经理' }, { id: 10, username: 'designer', realName: '李设计' }] }, timestamp: new Date().toISOString() })
    })
  })

  await context.route('**/api/customer-contacts**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: [{ id: 101, projectId: 11, name: 'Ember' }] }, timestamp: new Date().toISOString() })
    })
  })
})

test('需求菜单 badge 使用真实数量而不是写死 12', async ({ page }) => {
  await page.goto('/requirements')
  await expect(page.getByRole('link', { name: /需求管理/ }).locator('.nav-item-badge')).toHaveText('7')
})

test('独立需求详情页按路由 ID 加载真实接口数据', async ({ page }) => {
  await page.goto('/requirements-standalone/777')

  await expect(page.locator('.sidebar')).toHaveCount(0)
  await expect(page.locator('.req-no').first()).toHaveText('REQ-REAL-777')
  await expect(page.locator('.requirement-title')).toHaveText('真实接口需求标题')
  await expect(page.locator('.tag-list')).toContainText('皇家项目')
  await expect(page.locator('.tag-list')).toContainText('PMS')
  await expect(page.locator('.tag-list')).toContainText('全渠道云鹿定制')
  await expect(page.locator('.detail-sidebar')).toContainText('Ember')
  await expect(page.locator('.description-content')).toContainText('这是从接口返回的需求描述。')
})

test('需求描述过滤危险 HTML 并保留基础富文本格式', async ({ page }) => {
  const maliciousDescription = [
    '<p>安全内容 <strong>加粗文字</strong></p>',
    '<img src="invalid://image" onerror="window.__descriptionXss = true">',
    '<a href="javascript:window.__descriptionXss = true">危险链接</a>',
    '<script>window.__descriptionXss = true</script>'
  ].join('')

  await page.route('**/api/requirements/777', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: buildRequirement({ description: maliciousDescription }),
        timestamp: new Date().toISOString()
      })
    })
  })

  await page.goto('/requirements-standalone/777')

  const description = page.locator('.description-content')
  await expect(description.locator('strong')).toHaveText('加粗文字')
  await expect(description.locator('script')).toHaveCount(0)
  await expect(description.locator('img')).not.toHaveAttribute('onerror', /.+/)
  await expect(description.locator('a')).not.toHaveAttribute('href', /^javascript:/)
  await expect.poll(() => page.evaluate(() => (window as any).__descriptionXss)).toBeUndefined()
})

test('需求列表双击打开的新窗口详情页也接入真实接口数据', async ({ page }) => {
  await page.goto('/requirements')

  const popupPromise = page.waitForEvent('popup')
  await page.locator('.data-table tbody tr').filter({ hasText: '真实接口需求标题' }).dblclick()
  const popup = await popupPromise
  await popup.waitForLoadState('domcontentloaded')

  await expect(popup).toHaveURL(/\/requirements-standalone\/777/)
  await expect(popup.locator('.requirement-title')).toHaveText('真实接口需求标题')
  await expect(popup.locator('.req-no').first()).toHaveText('REQ-REAL-777')
})

test('需求详情页支持阶段推进：评估通过 -> 开始设计 -> 全部设计完成', async ({ page }) => {
  await page.goto('/requirements-standalone/777')

  await expect(page.locator('.status-badge').first()).toHaveText('评估中')
  await page.getByRole('button', { name: '配置设计' }).click()

  const planner = page.locator('.planner-modal')
  await expect(planner).toBeVisible()
  for (let i = 0; i < 3; i += 1) {
    await planner.locator('.plan-card').nth(i).locator('input[type="checkbox"]').check()
    await planner.locator('.plan-card').nth(i).locator('select').selectOption('10')
    await planner.locator('.plan-card').nth(i).locator('input[type="number"]').fill('8')
    await planner.locator('.plan-card').nth(i).locator('input[type="date"]').fill('2026-04-20')
  }
  await planner.getByRole('button', { name: '保存规划' }).click()

  await expect(page.locator('.action-panel')).toContainText('评估通过')

  await page.getByRole('button', { name: '评估通过' }).click()
  await confirmAction(page)
  await expect(page.locator('.status-badge').first()).toHaveText('待设计')

  await page.getByRole('button', { name: '开始设计' }).click()
  await confirmAction(page)
  await expect(page.locator('.status-badge').first()).toHaveText('设计中')
  await expect(page.locator('.design-work-item')).toHaveCount(3)

  await page.locator('.design-work-item .stage-select').nth(0).selectOption('已完成')
  await page.locator('.design-work-item .stage-select').nth(1).selectOption('已完成')
  await page.locator('.design-work-item .stage-select').nth(2).selectOption('已完成')

  await expect(page.locator('.status-badge').first()).toHaveText('待确认')
  await page.getByRole('button', { name: '客户确认' }).click()
  await confirmAction(page)
  await expect(page.locator('.status-badge').first()).toHaveText('开发中')
})

test('需求列表支持直接执行状态流转操作', async ({ page }) => {
  await page.goto('/requirements')

  const targetRow = page.locator('.data-table tbody tr').filter({ hasText: '另一条真实需求' })
  await expect(targetRow).toContainText('待评估')
  await targetRow.getByRole('button', { name: '开始评估' }).click()
  await confirmAction(page)
  await expect(targetRow).toContainText('评估中')
})
