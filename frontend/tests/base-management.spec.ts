import { expect, test } from '@playwright/test'

const mockBusinessLines = [
  { id: 1, name: '全渠道云鹿定制', description: '定制业务', status: 1 },
  { id: 2, name: '会员通', description: '会员通业务', status: 1 }
]

const mockProjects = [
  {
    id: 1,
    businessLineId: 1,
    parentId: null,
    level: 1,
    name: '皇家项目',
    fullPath: '皇家项目',
    code: 'ROYAL',
    managerId: 1,
    status: 1,
    children: [
      { id: 12, businessLineId: 1, parentId: 1, level: 2, name: 'PMS', fullPath: '皇家项目/PMS', code: 'ROYAL-PMS', managerId: 1, status: 1 }
    ]
  },
  { id: 2, businessLineId: 2, parentId: null, level: 1, name: '会员通系统', fullPath: '会员通系统', code: 'MEMBER', managerId: 1, status: 1 }
]

const mockUsers = [
  { id: 1, username: 'pm_zhang', realName: '张解决方案', role: 'SOLUTION_MANAGER', status: 1 },
  { id: 2, username: 'dev_zhao', realName: '赵全栈', role: 'FULL_STACK_ENGINEER', status: 1 }
]

const mockContacts = [
  { id: 1, projectId: 1, name: 'Ember', company: '皇家集团', position: '产品总监', phone: '13900000001', email: 'ember@royal.com', isActive: 1 }
]

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem(
      'user',
      JSON.stringify({
        id: 999,
        username: 'admin',
        realName: '系统管理员',
        role: 'DIRECTOR',
        email: 'admin@example.com',
        phone: '13800009999'
      })
    )
  })

  await page.route('**/api/business-lines**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: { records: mockBusinessLines },
        timestamp: new Date().toISOString()
      })
    })
  })

  await page.route('**/api/projects**', async route => {
    const pathname = new URL(route.request().url()).pathname
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: pathname === '/api/projects/tree'
          ? mockProjects
          : { records: mockProjects },
        timestamp: new Date().toISOString()
      })
    })
  })

  await page.route('**/api/users**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: { records: mockUsers },
        timestamp: new Date().toISOString()
      })
    })
  })

  await page.route('**/api/requirements**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: { records: [], total: 0 },
        timestamp: new Date().toISOString()
      })
    })
  })

  await page.route('**/api/customer-contacts**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: { records: mockContacts },
        timestamp: new Date().toISOString()
      })
    })
  })
})

test('基础分类导航与业务线路由可用', async ({ page }) => {
  await page.goto('/organization')

  await expect(page).toHaveURL(/\/business-lines$/)
  await expect(page.getByText('基础分类')).toBeVisible()
  await expect(page.getByRole('link', { name: '业务线管理' })).toBeVisible()
  await expect(page.getByRole('link', { name: '项目管理' })).toBeVisible()
  await expect(page.getByRole('link', { name: '客户信息管理' })).toBeVisible()
  await expect(page.locator('.page-title')).toHaveText('业务线管理')
  const firstCard = page.locator('[data-testid="business-line-card"]').first()
  await expect(page.locator('[data-testid=\"business-line-card\"]')).toHaveCount(2)
  await expect(firstCard).toContainText('全渠道云鹿定制')
  await expect(firstCard.locator('.status-badge')).toContainText('启用')
  await expect(firstCard.locator('.meta-block')).toHaveCount(2)
  await expect(firstCard.locator('.card-footer')).toContainText('编辑')
  await expect(firstCard.locator('.card-footer')).toContainText('删除')
  await expect(page.locator('.el-table')).toHaveCount(0)
})

test('项目管理的项目负责人下拉支持任意团队成员', async ({ page }) => {
  await page.goto('/projects')
  await page.getByRole('button', { name: '新增项目' }).click()

  const dialog = page.locator('.el-dialog').filter({ hasText: '新增项目' })
  const managerSelect = dialog.locator('.el-form-item').filter({ hasText: '项目负责人' }).locator('.el-select')
  await managerSelect.click()

  await expect(page.getByRole('option', { name: '张解决方案' })).toBeVisible()
  await expect(page.getByRole('option', { name: '赵全栈' })).toBeVisible()
})

test('项目管理支持直接编辑子项目', async ({ page }) => {
  await page.goto('/projects')

  await page.getByRole('button', { name: '编辑子项目 PMS' }).click()

  const dialog = page.locator('.el-dialog').filter({ hasText: '编辑项目' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.el-form-item').filter({ hasText: '名称' }).locator('input')).toHaveValue('PMS')
  await expect(dialog.locator('.el-form-item').filter({ hasText: '编码' }).locator('input')).toHaveValue('ROYAL-PMS')
  await expect(dialog.locator('.el-form-item').filter({ hasText: '父项目' }).locator('input')).toBeDisabled()

  await dialog.locator('.el-form-item').filter({ hasText: '名称' }).locator('input').fill('PMS升级')
  const updateRequest = page.waitForRequest(request =>
    request.method() === 'PUT' && new URL(request.url()).pathname === '/api/projects/12'
  )
  await dialog.getByRole('button', { name: '确定' }).click()

  const request = await updateRequest
  expect(request.postDataJSON()).toEqual({
    businessLineId: 1,
    parentId: 1,
    name: 'PMS升级',
    code: 'ROYAL-PMS',
    managerId: 1,
    status: 1
  })
})

test('项目管理支持删除子项目并发送DELETE请求', async ({ page }) => {
  await page.goto('/projects')

  const deleteRequest = page.waitForRequest(request =>
    request.method() === 'DELETE' && new URL(request.url()).pathname === '/api/projects/12'
  )
  await page.getByRole('button', { name: '删除子项目 PMS' }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确定' }).click()

  await expect(deleteRequest).resolves.toBeTruthy()
})

test('非管理序列角色在项目管理页面为只读', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem(
      'user',
      JSON.stringify({
        id: 998,
        username: 'dev_zhao',
        realName: '赵全栈',
        role: 'FULL_STACK_ENGINEER',
        email: 'dev@example.com',
        phone: '13800009998'
      })
    )
  })
  await page.goto('/projects')

  await expect(page.getByRole('button', { name: '新增项目' })).toHaveCount(0)
  await expect(page.locator('.card-footer')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '编辑子项目 PMS' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '删除子项目 PMS' })).toHaveCount(0)
  await expect(page.getByText('皇家项目').first()).toBeVisible()
})

test('客户信息管理页面展示联系人与关联项目', async ({ page }) => {
  await page.goto('/customers')

  await expect(page.locator('.page-title')).toHaveText('客户信息管理')
  await expect(page.locator('.el-table')).toContainText('Ember')
  await expect(page.locator('.el-table')).toContainText('皇家集团')
  await expect(page.locator('.el-table')).toContainText('皇家项目')
  await expect(page.locator('.el-table')).toContainText('全渠道云鹿定制')
})
