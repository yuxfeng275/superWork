import { expect, test } from '@playwright/test'

const mockUsers = [
  {
    id: 1,
    username: 'alice',
    realName: '艾丽丝',
    email: 'alice@example.com',
    phone: '13800000001',
    role: 'SOLUTION_MANAGER',
    status: 1,
    createTime: '2026-04-01T10:00:00Z'
  },
  {
    id: 2,
    username: 'bob',
    realName: '鲍勃',
    email: 'bob@example.com',
    phone: '13800000002',
    role: 'FULL_STACK_ENGINEER',
    status: 1,
    createTime: '2026-04-02T10:00:00Z'
  },
  {
    id: 3,
    username: 'cindy',
    realName: '辛迪',
    email: '',
    phone: '',
    role: 'FULL_STACK_ENGINEER',
    status: 0,
    createTime: '2026-04-03T10:00:00Z'
  }
]

test.beforeEach(async ({ page }) => {
  let usersState = mockUsers.map(user => ({ ...user }))

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

  await page.route('**/api/users**', async route => {
    if (route.request().method() !== 'GET') {
      await route.fallback()
      return
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: {
          records: usersState
        },
        timestamp: new Date().toISOString()
      })
    })
  })

  await page.route('**/api/users/*', async route => {
    const method = route.request().method()
    const userId = Number(route.request().url().split('/').pop())

    if (method === 'DELETE') {
      usersState = usersState.filter(user => user.id !== userId)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'success',
          data: null,
          timestamp: new Date().toISOString()
        })
      })
      return
    }

    if (method === 'PUT') {
      const payload = JSON.parse(route.request().postData() || '{}')
      usersState = usersState.map(user => (user.id === userId ? { ...user, ...payload } : user))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'success',
          data: usersState.find(user => user.id === userId),
          timestamp: new Date().toISOString()
        })
      })
      return
    }

    await route.fallback()
  })
})

test('用户管理按角色展示卡片', async ({ page }) => {
  await page.goto('/system/users')
  await expect(page.locator('.user-groups')).toBeVisible()
  await expect(page.locator('.table-card')).toHaveCount(0)

  const groups = page.locator('[data-testid="user-role-group"]')
  await expect(groups).toHaveCount(2)

  const pmGroup = groups.filter({ has: page.locator('.role-title', { hasText: '解决方案经理' }) })
  await expect(pmGroup.locator('[data-testid="user-card"]')).toHaveCount(1)

  const developerGroup = groups.filter({ has: page.locator('.role-title', { hasText: '全栈工程师' }) })
  await expect(developerGroup.locator('[data-testid="user-card"]')).toHaveCount(2)
  const developerCard = developerGroup.locator('[data-testid="user-card"]').nth(1)
  await developerCard.hover()
  await expect(developerCard.locator('.status-badge', { hasText: '停用' })).toBeVisible()

  const firstCard = page.locator('[data-testid="user-card"]').first()
  await expect(firstCard.locator('.user-card-compact .user-name')).toBeVisible()
  await expect(firstCard.locator('.user-card-quick-actions')).toBeVisible()
  await expect(firstCard.locator('.quick-action-btn')).toHaveCount(2)
  await expect(firstCard.locator('[data-testid="user-hover-panel"]')).toBeHidden()

  await firstCard.hover()
  await expect(firstCard.locator('[data-testid="user-hover-panel"]')).toBeVisible()
  await expect(firstCard.locator('.action-link.primary')).toHaveText('编辑')
  await expect(firstCard.locator('.action-link.danger')).toHaveText('删除')
})

test('顶部角色汇总点击后滚动到对应角色分组', async ({ page }) => {
  await page.addInitScript(() => {
    const originalScrollIntoView = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = function scrollIntoView(options?: boolean | ScrollIntoViewOptions) {
      ;(window as any).__lastRoleScrollTarget = this.getAttribute('data-role-group')
      return originalScrollIntoView.call(this, options)
    }
  })
  await page.goto('/system/users')

  const developerSummary = page.locator('[data-role-summary="FULL_STACK_ENGINEER"]')
  await developerSummary.click()

  await expect.poll(() => page.evaluate(() => (window as any).__lastRoleScrollTarget)).toBe('FULL_STACK_ENGINEER')
})

test('用户卡片支持编辑弹窗与删除流程', async ({ page }) => {
  await page.goto('/system/users')

  const firstCard = page.locator('[data-testid="user-card"]').first()
  await firstCard.locator('.quick-action-btn').first().click()

  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('编辑用户')
  await expect(dialog.locator('input[placeholder="请输入真实姓名"]')).toHaveValue('艾丽丝')
  await expect(dialog.locator('input[placeholder="请输入邮箱"]')).toHaveValue('alice@example.com')

  await dialog.getByRole('button', { name: '取消' }).click()
  await expect(dialog).not.toBeVisible()

  const developerGroup = page.locator('[data-testid="user-role-group"]').filter({
    has: page.locator('.role-title', { hasText: '全栈工程师' })
  })
  await expect(developerGroup.locator('[data-testid="user-card"]')).toHaveCount(2)

  const developerCard = developerGroup.locator('[data-testid="user-card"]').first()
  await developerCard.locator('.quick-action-btn.danger').click()
  await page.getByRole('button', { name: '确定' }).click()

  await expect(page.locator('.el-message--success')).toBeVisible()
  await expect(developerGroup.locator('[data-testid="user-card"]')).toHaveCount(1)
})
