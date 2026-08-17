import { expect, test } from '@playwright/test'

const mockRoles = [
  { id: 1, code: 'DIRECTOR', name: '总监', description: '部门第一负责人', status: 1, createdAt: '2026-04-01' }
]

const mockMenus = [
  { id: 10, parentId: 0, name: '需求管理', path: '/requirements', sortOrder: 1, status: 1 },
  { id: 11, parentId: 10, name: '需求列表', path: '/requirements', sortOrder: 2, status: 1 }
]

const mockPermissions = [
  { id: 101, code: 'requirement:create', name: '创建需求', type: 'button', menuId: 10 },
  { id: 102, code: 'requirement:edit', name: '编辑需求', type: 'button', menuId: 10 }
]

const mockBusinessLines = [
  { id: 1, name: '全渠道云鹿定制', status: 1 },
  { id: 2, name: '会员通', status: 1 }
]

const mockProjects = [
  { id: 11, businessLineId: 1, name: '皇家项目', status: 1 },
  { id: 22, businessLineId: 2, name: '会员通系统', status: 1 }
]

test.beforeEach(async ({ page }) => {
  let lastAuthorizationPayload: unknown = null

  await page.route('**/api/system/roles**', async route => {
    const pathname = new URL(route.request().url()).pathname

    if (pathname === '/api/system/roles') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mockRoles) })
      return
    }

    if (pathname === '/api/system/roles/1/authorization') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ menuIds: [10, 11], permissionIds: [101], dataScope: 'BU_LINE', dataScopeValue: '1' })
      })
      return
    }

    if (pathname === '/api/system/roles/authorization/assign') {
      lastAuthorizationPayload = JSON.parse(route.request().postData() || '{}')
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({}) })
      return
    }

    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({}) })
  })

  await page.route('**/api/system/menus', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mockMenus) })
  })

  await page.route('**/api/system/permissions', async route => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mockPermissions) })
  })

  await page.route('**/api/business-lines**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: mockBusinessLines }, timestamp: new Date().toISOString() })
    })
  })

  await page.route('**/api/projects**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: mockProjects }, timestamp: new Date().toISOString() })
    })
  })

  await page.route('**/api/requirements**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: [], total: 0 }, timestamp: new Date().toISOString() })
    })
  })

  await page.exposeFunction('getLastAuthorizationPayload', () => lastAuthorizationPayload)

  await page.goto('/login')
  await page.evaluate(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem(
      'user',
      JSON.stringify({ id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' })
    )
  })
})

test('角色管理授权弹窗按钮权限整行可点，数据范围使用选择控件', async ({ page }) => {
  await page.goto('/system/roles')
  await expect(page.getByRole('button', { name: '新增角色' })).toHaveCount(0)
  await expect(page.locator('.position-sequence')).toHaveCount(2)
  await expect(page.locator('.position-sequence').first()).toContainText('管理序列')
  await expect(page.locator('.position-sequence').nth(1)).toContainText('执行序列')
  await expect(page.locator('.position-role-card')).toHaveCount(11)

  await page.locator('.action-link', { hasText: '配置授权' }).click()

  const dialog = page.locator('.el-dialog').filter({ hasText: '配置授权' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.auth-menu .el-tree')).toBeVisible()
  await expect(dialog.locator('.auth-buttons')).toBeVisible()

  await expect(dialog.locator('.button-menu-name')).toContainText('需求管理')

  const editPermission = dialog.locator('.btn-item').filter({ hasText: '编辑需求' })
  await editPermission.click()
  await expect(editPermission).toHaveClass(/btn-checked/)

  await expect(dialog.locator('.ds-value-row')).toContainText('业务线')
  await expect(dialog.locator('.ds-value-row .el-select')).toHaveCount(0)
  await expect(dialog.locator('.scope-choice', { hasText: '全渠道云鹿定制' })).toHaveClass(/active/)

  await dialog.locator('.el-radio').filter({ hasText: '按项目/部门' }).click()
  await expect(dialog.locator('.ds-value-row')).toContainText('项目')
  await expect(dialog.locator('.ds-value-row .el-select')).toHaveCount(0)
  await dialog.locator('.scope-choice', { hasText: '皇家项目' }).click()
  await expect(dialog.locator('.scope-choice', { hasText: '皇家项目' })).toHaveClass(/active/)

  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.el-message--success')).toBeVisible()

  const payload = await page.evaluate(() => (window as any).getLastAuthorizationPayload())
  expect(payload.dataScope).toBe('PROJECT')
  expect(payload.dataScopeValue).toBe('11')
})
