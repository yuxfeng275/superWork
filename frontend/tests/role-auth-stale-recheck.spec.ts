import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

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

// 模拟生产菜单形态：根菜单（首页 1 / 大事儿管理 16 …）+ 带子菜单的分组（系统管理 7 → 8/9/10）
const mockRoles = [
  { id: 1, code: 'DIRECTOR', name: '总监', description: '部门第一负责人', status: 1, createdAt: '2026-04-01' }
]

const mockMenus = [
  { id: 1, parentId: 0, name: '首页', path: '/home', sortOrder: 1, status: 1 },
  { id: 2, parentId: 0, name: '需求管理', path: '/requirements', sortOrder: 2, status: 1 },
  { id: 3, parentId: 0, name: '事项管理', path: '/issues', sortOrder: 3, status: 1 },
  { id: 4, parentId: 0, name: '任务管理', path: '/tasks', sortOrder: 4, status: 1 },
  { id: 16, parentId: 0, name: '大事儿管理', path: '/key-matters', sortOrder: 5, status: 1 },
  { id: 7, parentId: 0, name: '系统管理', path: '/system', sortOrder: 100, status: 1 },
  { id: 8, parentId: 7, name: '用户管理', path: '/system/users', sortOrder: 101, status: 1 },
  { id: 9, parentId: 7, name: '角色管理', path: '/system/roles', sortOrder: 102, status: 1 },
  { id: 10, parentId: 7, name: '菜单管理', path: '/system/menus', sortOrder: 103, status: 1 }
]

const mockPermissions: unknown[] = []

const sortNums = (nums: number[]): number[] => [...nums].sort((a, b) => a - b)

const menuContent = (dialog: Locator, name: string): Locator =>
  dialog.locator('.el-tree-node__content', { has: dialog.page().locator('.el-tree-node__label', { hasText: name }) })

// 勾选态 class 打在 el-checkbox 根 label 上；半选态 is-indeterminate 打在内部 .el-checkbox__input 上
const checkbox = (dialog: Locator, name: string): Locator =>
  menuContent(dialog, name).locator('.el-checkbox')

const checkboxInput = (dialog: Locator, name: string): Locator =>
  menuContent(dialog, name).locator('.el-checkbox__input')

const expectChecked = async (dialog: Locator, name: string): Promise<void> => {
  await expect(checkbox(dialog, name)).toHaveClass(/is-checked/)
}

const expectUnchecked = async (dialog: Locator, name: string): Promise<void> => {
  await expect(checkbox(dialog, name)).not.toHaveClass(/is-checked/)
  await expect(checkboxInput(dialog, name)).not.toHaveClass(/is-indeterminate/)
}

const expectIndeterminate = async (dialog: Locator, name: string): Promise<void> => {
  await expect(checkbox(dialog, name)).not.toHaveClass(/is-checked/)
  await expect(checkboxInput(dialog, name)).toHaveClass(/is-indeterminate/)
}

async function openAuthDialog(page: Page): Promise<Locator> {
  await page.locator('.action-link', { hasText: '配置授权' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '配置授权' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.el-tree-node')).toHaveCount(mockMenus.length)
  return dialog
}

async function saveAndConfirm(dialog: Locator): Promise<void> {
  await dialog.getByRole('button', { name: '保存' }).click()
  const confirmBox = dialog.page().locator('.el-message-box')
  await expect(confirmBox).toBeVisible()
  await confirmBox.getByRole('button', { name: '确认保存' }).click()
  await expect(dialog.page().locator('.el-message--success')).toBeVisible()
}

async function initialStateFor(page: Page, menuIds: number[]): Promise<void> {
  const savedAuthorization: AuthorizationPayload = { menuIds, permissionIds: [], dataScope: 'SELF', dataScopeValue: '' }

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
}

test.describe('角色授权取消勾选路径', () => {
  test('从 8 个已授权菜单改到只保留「首页」「大事儿管理」，重开不应残留旧勾选', async ({ page }) => {
    // 初始 8 个已授权菜单（含分组 系统管理 下的子菜单 8/9，但同组 菜单管理 10 未授权）
    await initialStateFor(page, [1, 2, 3, 4, 16, 7, 8, 9])

    await page.goto('/system/roles')
    const dialog = await openAuthDialog(page)

    // 初始回显：授权节点勾选；未授权的「菜单管理 10」不得被级联勾出，父级 系统管理 应推导为半选
    await expectChecked(dialog, '首页')
    await expectChecked(dialog, '大事儿管理')
    await expectChecked(dialog, '用户管理')
    await expectChecked(dialog, '角色管理')
    await expectIndeterminate(dialog, '系统管理')
    await expectUnchecked(dialog, '菜单管理')

    // 先全部取消，再只勾选「首页」「大事儿管理」
    await dialog.getByRole('button', { name: '菜单清空' }).click()
    await expectUnchecked(dialog, '首页')
    await checkbox(dialog, '首页').click()
    await checkbox(dialog, '大事儿管理').click()

    await saveAndConfirm(dialog)
    await expect(dialog).toBeHidden()

    // 保存载荷应只含这两个菜单 id（均为根菜单，无父级补充）
    const payload = await page.evaluate(() => window.getLastAuthorizationPayload())
    expect(payload && sortNums(payload.menuIds)).toEqual([1, 16])

    // 重新打开同一角色：应只有「首页」「大事儿管理」被勾选，其余节点无勾选/无半选残留
    const reopened = await openAuthDialog(page)
    await expectChecked(reopened, '首页')
    await expectChecked(reopened, '大事儿管理')
    for (const name of ['需求管理', '事项管理', '任务管理', '系统管理', '用户管理', '角色管理', '菜单管理']) {
      await expectUnchecked(reopened, name)
    }
  })

  test('保留分组内一个子菜单、移除同组其他子菜单，重开不得把已移除的同级勾回来', async ({ page }) => {
    // 初始全选形态：所有菜单均已授权
    await initialStateFor(page, [1, 2, 3, 4, 16, 7, 8, 9, 10])

    await page.goto('/system/roles')
    const dialog = await openAuthDialog(page)
    await expectChecked(dialog, '系统管理')

    // 清空后只勾「首页」「大事儿管理」和分组 系统管理 下的「用户管理」
    await dialog.getByRole('button', { name: '菜单清空' }).click()
    await checkbox(dialog, '首页').click()
    await checkbox(dialog, '大事儿管理').click()
    await checkbox(dialog, '用户管理').click()

    await saveAndConfirm(dialog)
    await expect(dialog).toBeHidden()

    // 保存载荷：叶子 1/16/8 + 父级 7
    const payload = await page.evaluate(() => window.getLastAuthorizationPayload())
    expect(payload && sortNums(payload.menuIds)).toEqual([1, 7, 8, 16])

    // 重新打开：同组已被移除的「角色管理 9」「菜单管理 10」不得被勾选，
    // 父级「系统管理 7」应推导为半选而非全选
    const reopened = await openAuthDialog(page)
    await expectChecked(reopened, '首页')
    await expectChecked(reopened, '大事儿管理')
    await expectChecked(reopened, '用户管理')
    await expectIndeterminate(reopened, '系统管理')
    for (const name of ['角色管理', '菜单管理', '需求管理', '事项管理', '任务管理']) {
      await expectUnchecked(reopened, name)
    }
  })
})
