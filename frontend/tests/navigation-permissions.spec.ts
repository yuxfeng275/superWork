import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem(
      'user',
      JSON.stringify({
        id: 3,
        username: 'shijiale',
        realName: '石家乐',
        role: 'FULL_STACK_ENGINEER'
      })
    )
  })

  await page.route('**/api/key-matters/access', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: { canAccess: false, canManageAll: false, canFeedbackOwn: false },
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
})

test('执行岗位不展示系统管理入口且不能直接进入系统路由', async ({ page }) => {
  await page.goto('/')

  await expect(page.locator('.nav-section-title', { hasText: '系统' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '用户管理' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '数据统计' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '客户信息管理' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '项目管理' })).toHaveCount(1)
  await expect(page.getByRole('link', { name: '大事儿管理' })).toHaveCount(0)

  await page.goto('/system/users')
  await expect(page).toHaveURL('/')

  await page.goto('/statistics')
  await expect(page).toHaveURL('/')

  await page.goto('/key-matters')
  await expect(page).toHaveURL('/')
})
