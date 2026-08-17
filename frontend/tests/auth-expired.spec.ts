import { expect, test } from '@playwright/test'

test('接口返回 401 时清理登录态并跳转登录页', async ({ page }) => {
  await page.goto('/login')
  await page.evaluate(() => {
    localStorage.setItem('token', 'expired-token')
    localStorage.setItem('refreshToken', 'expired-refresh-token')
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

  await page.route('**/api/users**', async route => {
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 401,
        message: '登录已失效，请重新登录',
        data: null,
        timestamp: new Date().toISOString()
      })
    })
  })

  await page.goto('/system/users')

  await page.waitForURL('**/login')
  await expect(page).toHaveURL(/\/login$/)
  await expect(
    page.evaluate(() => ({
      token: localStorage.getItem('token'),
      refreshToken: localStorage.getItem('refreshToken'),
      user: localStorage.getItem('user')
    }))
  ).resolves.toEqual({
    token: null,
    refreshToken: null,
    user: null
  })
})
