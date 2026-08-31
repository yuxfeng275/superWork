import { expect, test } from '@playwright/test'

const dashboard = {
  periodStart: '2026-07-01',
  periodEnd: '2026-07-30',
  planWindowWorkdays: 10,
  summary: {
    directionCount: 2,
    atRiskDirectionCount: 1,
    activeRequirementCount: 8,
    overdueTaskCount: 2,
    overloadedPeopleCount: 1,
    missingWorklogPeopleCount: 1
  },
  directions: [
    {
      id: 1,
      code: '2026-Q3-ROYAL',
      name: '皇家会员运营提效',
      objective: '按季度计划完成核心会员链路优化',
      ownerId: 16,
      ownerName: '于峰',
      startDate: '2026-07-01',
      endDate: '2026-09-30',
      status: '进行中',
      sortOrder: 1,
      progress: 58.5,
      health: '有风险',
      requirementCount: 12,
      completedRequirementCount: 6,
      taskCount: 24,
      completedTaskCount: 14,
      projectIds: [1],
      projectNames: ['皇家 omniCRM'],
      milestones: [
        {
          id: 1,
          name: '会员链路联调',
          dueDate: '2026-07-25',
          status: '进行中',
          sortOrder: 1,
          overdue: true
        }
      ]
    }
  ],
  capacity: [
    {
      userId: 7,
      realName: '石家乐',
      role: 'FULL_STACK_ENGINEER',
      activeTaskCount: 5,
      overdueTaskCount: 1,
      actualHours: 136,
      expectedHours: 176,
      actualEffortRate: 77.3,
      plannedHours: 92,
      plannedLoadRate: 115,
      loadStatus: '饱和',
      dataCompleteness: '云效数据已同步',
      yunxiaoMapped: true,
      activeWork: ['会员标签重构', '积分接口优化']
    }
  ],
  worklogs: [
    {
      userId: 7,
      realName: '石家乐',
      role: 'FULL_STACK_ENGINEER',
      workDate: '2026-07-29',
      expectedHours: 8,
      actualHours: 0,
      status: '未填写',
      finalResult: true
    },
    {
      userId: 15,
      realName: '黄金玲',
      role: 'QUALITY_ENGINEER',
      workDate: '2026-07-29',
      expectedHours: 8,
      actualHours: 8,
      status: '已填写',
      finalResult: true
    }
  ],
  integration: {
    enabled: false,
    configured: false,
    edition: 'center',
    baseUrl: 'https://openapi-rdc.aliyuncs.com',
    organizationId: '',
    tokenConfigured: false,
    tokenSource: 'NONE',
    organizationConfigured: false,
    mappedProjects: 0,
    mappedUsers: 0
  }
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem(
      'user',
      JSON.stringify({
        id: 1,
        username: 'admin',
        realName: '系统管理员',
        role: 'DIRECTOR'
      })
    )
  })

  await page.route('**/api/requirements**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: { records: [], total: 0 } })
  }))
  await page.route('**/api/business-lines**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: { records: [], total: 0 } })
  }))
  await page.route('**/api/yunxiao/analysis', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: { byOwner: [], total: 0, requirements: 0, tasks: 0, delayed: 0 } })
  }))
  await page.route('**/api/bu-dashboard**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: dashboard })
  }))
  await page.route('**/api/projects**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: { records: [{ id: 1, name: '皇家 omniCRM' }] } })
  }))
  await page.route('**/api/users**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 200,
      data: {
        records: [
          { id: 7, username: 'shijiale', realName: '石家乐', role: 'FULL_STACK_ENGINEER' },
          { id: 16, username: 'yufeng', realName: '于峰', role: 'BUSINESS_OWNER' }
        ]
      }
    })
  }))
  await page.route('**/api/yunxiao/project-mappings', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: [] })
  }))
  await page.route('**/api/yunxiao/projects', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 200,
      data: [
        {
          id: 'yunxiao-project-royal',
          name: '皇家 omniCRM',
          customCode: 'ROYAL',
          status: 'NORMAL'
        },
        ...Array.from({ length: 11 }, (_, index) => ({
          id: `yunxiao-project-${index + 1}`,
          name: `云效项目 ${index + 1}`,
          customCode: `CODE${index + 1}`,
          status: 'NORMAL'
        }))
      ]
    })
  }))
  await page.route('**/api/yunxiao/user-mappings', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: [] })
  }))
  await page.route('**/api/yunxiao/members', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 200,
      data: [
        {
          userId: 'yunxiao-user-yufeng',
          memberId: 'yunxiao-member-yufeng',
          name: '于峰',
          email: 'yufeng@example.com',
          status: 'ENABLED'
        },
        {
          userId: '69dc6477df2584eb75d43c9b',
          memberId: '69dc6acc405bafb07e129706',
          name: 'nicholas_jintao@hotmail.com',
          email: '',
          status: 'ENABLED'
        }
      ]
    })
  }))
  await page.route('**/api/yunxiao/config', async route => {
    const request = route.request().postDataJSON()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          ...dashboard.integration,
          enabled: request.enabled,
          configured: true,
          edition: request.edition,
          baseUrl: request.baseUrl,
          organizationId: request.organizationId,
          tokenConfigured: true,
          tokenSource: 'PAGE',
          organizationConfigured: true
        }
      })
    })
  })
  await page.route('**/api/yunxiao/connection-test', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 200,
      data: {
        success: true,
        userId: 'yunxiao-user-16',
        userName: '于峰',
        email: 'yufeng@example.com',
        message: '连接成功',
        testedAt: '2026-07-30T15:30:00'
      }
    })
  }))
})

test('BU负责人可查看人员负荷、工时分布和云效状态', async ({ page }) => {
  await page.goto('/statistics')

  await expect(page.getByText('BU驾驶舱', { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('tab', { name: '方向总览' })).toHaveCount(0)
  await expect(page.getByText('周期实际工时')).toBeVisible()

  await page.getByRole('tab', { name: '人员负荷' }).click()
  const capacityPanel = page.getByRole('tabpanel', { name: '人员负荷' })
  await expect(capacityPanel.getByText('石家乐')).toBeVisible()
  await expect(capacityPanel.getByText('饱和', { exact: true })).toBeVisible()
  await expect(capacityPanel.getByText('会员标签重构')).toBeVisible()
  await expect(capacityPanel.getByText(/不作为绩效评价/)).toBeVisible()

  await page.getByRole('tab', { name: '工时检查' }).click()
  const worklogPanel = page.getByRole('tabpanel', { name: '工时检查' })
  await expect(worklogPanel.getByText('填写状态分布')).toBeVisible()
  await expect(worklogPanel.getByText('成员填写分布')).toBeVisible()
  await expect(worklogPanel.getByText('填写完成率')).toBeVisible()
  await expect(worklogPanel.getByRole('button', { name: '查看已填写 1 条记录' })).toBeVisible()
  await expect(worklogPanel.getByRole('button', { name: '查看未填写 1 条记录' })).toBeVisible()
  await expect(worklogPanel.getByLabel('石家乐工时状态：未填写')).toBeVisible()
  await worklogPanel.getByRole('button', { name: '查看已填写 1 条记录' }).click()
  const worklogTable = worklogPanel.locator('.data-table')
  await expect(worklogTable.getByText('黄金玲')).toBeVisible()
  await expect(worklogTable.getByText('石家乐')).toBeHidden()
  await expect(worklogPanel.getByText('最终结果', { exact: true })).toBeVisible()

  await page.getByRole('tab', { name: '云效配置' }).click()
  await expect(page.getByText('云效集成已停用')).toBeVisible()
  await expect(page.getByRole('button', { name: '立即同步' })).toBeDisabled()

  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > document.documentElement.clientWidth
  )
  expect(overflow).toBe(false)
})

test('方向总览已从BU驾驶舱移除', async ({ page }) => {
  await page.goto('/statistics')
  await expect(page.getByRole('tab', { name: '方向总览' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新增方向' })).toHaveCount(0)
})

test('云效连接参数可在页面保存并测试且令牌不回显', async ({ page }) => {
  await page.goto('/statistics')
  await page.getByRole('tab', { name: '云效配置' }).click()

  const panel = page.getByRole('tabpanel', { name: '云效配置' })
  await panel.locator('.el-switch').click()
  await panel.getByPlaceholder('云效企业组织ID').fill('org-royal')
  await panel.getByPlaceholder('输入个人访问令牌').fill('pat-secret')

  const configRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/yunxiao/config') && request.method() === 'PUT'
  )
  const connectionRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/yunxiao/connection-test') && request.method() === 'POST'
  )
  await panel.getByRole('button', { name: '测试连接' }).click()

  const savedRequest = await configRequest
  expect(savedRequest.postDataJSON()).toMatchObject({
    enabled: true,
    edition: 'center',
    organizationId: 'org-royal',
    token: 'pat-secret'
  })
  await connectionRequest
  await expect(page.getByText('连接成功：于峰')).toBeVisible()
  await expect(panel.getByPlaceholder('已配置，留空保持不变')).toHaveValue('')
  await expect(panel.getByText('pat-secret')).toHaveCount(0)
})

test('项目映射从云效项目列表选择并保存稳定ID', async ({ page }) => {
  await page.goto('/statistics')
  await page.getByRole('tab', { name: '云效配置' }).click()
  await page.getByRole('button', { name: '新增映射' }).first().click()

  const dialog = page.getByRole('dialog', { name: '项目映射' })
  const yunxiaoProjectField = dialog.locator('.el-form-item').filter({ hasText: '云效项目' })
  const yunxiaoProjectSelect = yunxiaoProjectField.getByRole('combobox')
  await expect(yunxiaoProjectSelect).toBeVisible()
  await yunxiaoProjectField.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: /皇家 omniCRM/ }).click()

  await dialog.locator('.el-form-item').filter({ hasText: '本地项目' })
    .locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '皇家 omniCRM', exact: true }).click()

  const saveRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/yunxiao/project-mappings')
      && request.method() === 'POST'
  )
  await dialog.getByRole('button', { name: '保存', exact: true }).click()

  expect((await saveRequest).postDataJSON()).toMatchObject({
    projectId: 1,
    yunxiaoProjectId: 'yunxiao-project-royal'
  })
})

test('项目映射弹窗在移动端不产生页面横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/statistics')
  await page.getByRole('tab', { name: '云效配置' }).click()
  await page.getByRole('button', { name: '新增映射' }).first().click()

  const dialog = page.getByRole('dialog', { name: '项目映射' })
  await expect(dialog).toBeVisible()
  const yunxiaoProjectField = dialog.locator('.el-form-item').filter({ hasText: '云效项目' })
  await yunxiaoProjectField.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: /皇家 omniCRM/ }).waitFor()
  const saveButton = dialog.getByRole('button', { name: '保存', exact: true })
  const saveButtonBox = await saveButton.boundingBox()
  const metrics = await page.evaluate(() => {
    const element = document.querySelector('.el-dialog')
    const rect = element?.getBoundingClientRect()
    return {
      documentOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      dialogInsideViewport: Boolean(rect && rect.left >= 0 && rect.right <= window.innerWidth)
    }
  })
  const popupAboveFooter = saveButtonBox && await page.evaluate(({ x, y }) =>
    Boolean(document.elementFromPoint(x, y)?.closest('.yunxiao-project-popper')),
  { x: saveButtonBox.x + 4, y: saveButtonBox.y + 4 })

  expect(metrics).toEqual({ documentOverflow: false, dialogInsideViewport: true })
  expect(popupAboveFooter).toBe(true)
})

test('人员映射从云效成员列表选择并保存用户ID', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/statistics')
  await page.getByRole('tab', { name: '云效配置' }).click()
  await page.getByRole('button', { name: '新增映射' }).last().click()

  const dialog = page.getByRole('dialog', { name: '人员映射' })
  const yunxiaoMemberField = dialog.locator('.el-form-item').filter({ hasText: '云效人员' })
  await expect(yunxiaoMemberField.getByRole('combobox')).toBeVisible()
  await yunxiaoMemberField.locator('.el-select__wrapper').click()
  await expect(page.getByRole('option', { name: /nicholas_jintao/ })).toBeVisible()
  expect(await page.evaluate(() =>
    document.documentElement.scrollWidth > document.documentElement.clientWidth
  )).toBe(false)
  await page.getByRole('option', { name: /于峰/ }).click()

  await dialog.locator('.el-form-item').filter({ hasText: '本地人员' })
    .locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '于峰 · 经营负责人', exact: true }).click()

  const saveRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/yunxiao/user-mappings')
      && request.method() === 'POST'
  )
  await dialog.getByRole('button', { name: '保存', exact: true }).click()

  expect((await saveRequest).postDataJSON()).toMatchObject({
    userId: 16,
    yunxiaoUserId: 'yunxiao-user-yufeng'
  })
})

test('项目映射按主子项目树形展示并显示云效项目名称', async ({ page }, testInfo) => {
  await page.unroute('**/api/projects**')
  await page.route('**/api/projects**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 200,
      data: {
        records: [
          { id: 1, name: '皇家 omniCRM', parentId: null, businessLineId: 3 },
          { id: 2, name: '会员通积分子项目', parentId: 1, businessLineId: null }
        ],
        total: 2
      }
    })
  }))
  await page.unroute('**/api/business-lines**')
  await page.route('**/api/business-lines**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 200,
      data: { records: [{ id: 3, name: '定制开发' }], total: 1 }
    })
  }))
  await page.unroute('**/api/yunxiao/project-mappings')
  await page.route('**/api/yunxiao/project-mappings', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 200,
      data: [
        { id: 11, projectId: 1, yunxiaoProjectId: 'yunxiao-project-royal', workitemTypeId: 'type-req', category: 'Req', syncEnabled: 1, lastSyncStatus: 'SUCCESS' },
        { id: 12, projectId: 2, yunxiaoProjectId: 'yunxiao-project-1', workitemTypeId: '', category: 'Req', syncEnabled: 1, lastSyncStatus: 'SUCCESS' }
      ]
    })
  }))

  await page.goto('/statistics')
  await page.getByRole('tab', { name: '云效配置' }).click()

  const section = page.locator('.mapping-section').filter({ hasText: '项目映射' })
  const table = section.locator('.data-table')
  await expect(table.getByRole('columnheader', { name: '云效项目名称' })).toBeVisible()

  const rows = table.locator('tbody tr')
  await expect(rows).toHaveCount(2)
  await expect(rows.nth(0)).toContainText('皇家 omniCRM')
  await expect(rows.nth(0)).toContainText('定制开发')
  await expect(rows.nth(0)).toContainText('皇家 omniCRM · ROYAL')
  await expect(rows.nth(0).locator('.mapping-root-name')).toBeVisible()
  await expect(rows.nth(1)).toContainText('会员通积分子项目')
  await expect(rows.nth(1)).toContainText('定制开发')
  await expect(rows.nth(1)).toContainText('云效项目 1 · CODE1')
  await expect(rows.nth(1).locator('.el-table__indent')).toHaveCount(1)
  if (process.env.CAPTURE_BU_DASHBOARD === '1') {
    await section.screenshot({ path: testInfo.outputPath('project-mapping-tree.png') })
  }
})
