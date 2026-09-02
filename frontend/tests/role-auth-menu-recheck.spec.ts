import { expect, test } from '@playwright/test'
import type { Locator } from '@playwright/test'

interface AuthorizationPayload {
  menuIds: number[]
  permissionIds: number[]
  dataScope: string
  dataScopeValue: string
}

declare global {
  interface Window {
    getLastAuthorizationPayload: () => AuthorizationPayload | null
  }
}

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

const menuContent = (dialog: Locator, name: string): Locator =>
  dialog.locator('.el-tree-node__content', { has: dialog.page().locator('.el-tree-node__label', { hasText: name }) })

test.beforeEach(async ({ page }) => {
  // 有状态的授权数据：保存后再次查询时返回最新值，模拟后端持久化
  const savedAuthorization: AuthorizationPayload = { menuIds: [], permissionIds: [], dataScope: 'SELF', dataScopeValue: '' }

  let lastAuthorizationPayload: AuthorizationPayload | null = null

  await page.route('**/api/system/roles**', async route => {
    const pathname = new URL(route.request().url()).pathname

    if (pathname === '/api/system/roles') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mockRoles) })
      return
    }

    if (pathname === '/api/system/roles/1/authorization') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(savedAuthorization) })
      return
    }

    if (pathname === '/api/system/roles/authorization/assign') {
      const body = route.request().postDataJSON() as AuthorizationPayload
      lastAuthorizationPayload = body
      savedAuthorization.menuIds = body.menuIds
      savedAuthorization.permissionIds = body.permissionIds
      savedAuthorization.dataScope = body.dataScope
      savedAuthorization.dataScopeValue = body.dataScopeValue
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
      body: JSON.stringify({ code: 200, message: 'success', data: { records: [] }, timestamp: new Date().toISOString() })
    })
  })

  await page.route('**/api/projects**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: [] }, timestamp: new Date().toISOString() })
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

test('配置授权保存后重新打开，菜单勾选应回显', async ({ page }) => {
  await page.goto('/system/roles')

  await page.locator('.action-link', { hasText: '配置授权' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '配置授权' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.el-tree-node')).toHaveCount(2)

  // 初始未保存任何勾选
  await expect(menuContent(dialog, '需求列表').locator('.el-checkbox')).not.toHaveClass(/is-checked/)

  // 勾选叶子菜单「需求列表」并保存
  await menuContent(dialog, '需求列表').locator('.el-checkbox').click()
  await expect(menuContent(dialog, '需求列表').locator('.el-checkbox')).toHaveClass(/is-checked/)
  await dialog.getByRole('button', { name: '保存' }).click()

  // 有变更时弹出确认框
  const confirmBox = page.locator('.el-message-box')
  await expect(confirmBox).toBeVisible()
  await confirmBox.getByRole('button', { name: '确认保存' }).click()
  await expect(page.locator('.el-message--success')).toBeVisible()

  // 保存请求应携带勾选的菜单（含父级）
  const payload = await page.evaluate(() => window.getLastAuthorizationPayload())
  expect(payload?.menuIds).toEqual(expect.arrayContaining([10, 11]))

  // 关闭弹窗后重新打开，勾选应回显
  await expect(dialog).toBeHidden()
  await page.locator('.action-link', { hasText: '配置授权' }).click()
  const reopenedDialog = page.locator('.el-dialog').filter({ hasText: '配置授权' })
  await expect(reopenedDialog).toBeVisible()
  await expect(reopenedDialog.locator('.el-tree-node')).toHaveCount(2)

  await expect(menuContent(reopenedDialog, '需求列表').locator('.el-checkbox')).toHaveClass(/is-checked/)
  await expect(menuContent(reopenedDialog, '需求管理').locator('.el-checkbox')).toHaveClass(/is-checked/)
})
