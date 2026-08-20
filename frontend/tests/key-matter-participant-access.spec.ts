import { expect, test, type Page } from '@playwright/test'

interface TestUser {
  id: number
  username: string
  realName: string
  role: string
}

interface KeyMatterAccessFixture {
  canAccess: boolean
  canManageAll: boolean
  canFeedbackOwn: boolean
}

const emptyKeyMatterResponse = {
  code: 200,
  message: 'success',
  data: [],
  timestamp: '2026-03-20T00:00:00.000Z'
}

async function mockSession(
  page: Page,
  user: TestUser,
  access: unknown
) {
  const requestCounts = { access: 0, requirements: 0 }

  await page.addInitScript(({ currentUser }) => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify(currentUser))
  }, { currentUser: user })

  await page.route('**/api/key-matters**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/key-matters/access') {
      requestCounts.access += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'success',
          data: access,
          timestamp: '2026-03-20T00:00:00.000Z'
        })
      })
      return
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(emptyKeyMatterResponse)
    })
  })

  await page.route('**/api/requirements**', async route => {
    requestCounts.requirements += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: { records: [], total: 0 },
        timestamp: '2026-03-20T00:00:00.000Z'
      })
    })
  })

  await page.route('**/api/projects**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: [], total: 0 } })
    })
  })

  await page.route('**/api/users**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: [], total: 0 } })
    })
  })

  return requestCounts
}

const authorizedCases: Array<{
  label: string
  user: TestUser
  access: KeyMatterAccessFixture
}> = [
  {
    label: '事项负责人石家乐',
    user: { id: 3, username: 'shijiale', realName: '石家乐', role: 'FULL_STACK_ENGINEER' },
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true }
  },
  {
    label: '仅参与人',
    user: { id: 8, username: 'participant', realName: '参与人', role: 'FULL_STACK_ENGINEER' },
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: false }
  },
  {
    label: '管理员',
    user: { id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' },
    access: { canAccess: true, canManageAll: true, canFeedbackOwn: true }
  }
]

for (const { label, user, access } of authorizedCases) {
  test(`${label}可见菜单并可进入台账和独立周会路由`, async ({ page }) => {
    const requestCounts = await mockSession(page, user, access)

    await page.goto('/key-matters')

    await expect(page).toHaveURL('/key-matters')
    await expect(page.getByRole('link', { name: '大事儿管理' })).toBeVisible()
    await expect.poll(() => requestCounts.access).toBe(1)
    await expect.poll(() => requestCounts.requirements).toBe(1)

    await page.goto('/key-matters-meeting')

    await expect(page).toHaveURL('/key-matters-meeting')
    await expect(page.locator('.layout')).toHaveCount(0)
    await expect(page.getByRole('link', { name: '大事儿管理' })).toHaveCount(0)
    await expect.poll(() => requestCounts.access).toBe(2)
    expect(requestCounts.requirements).toBe(1)
  })
}

test('无关用户看不到菜单且直达台账和周会均返回首页', async ({ page }) => {
  const requestCounts = await mockSession(
    page,
    { id: 9, username: 'unrelated', realName: '无关用户', role: 'FULL_STACK_ENGINEER' },
    { canAccess: false, canManageAll: false, canFeedbackOwn: false }
  )

  await page.goto('/')
  await expect(page.getByRole('link', { name: '大事儿管理' })).toHaveCount(0)
  await expect.poll(() => requestCounts.access).toBe(1)

  await page.goto('/key-matters')
  await expect(page).toHaveURL('/')
  await expect(page.getByRole('link', { name: '大事儿管理' })).toHaveCount(0)
  await expect.poll(() => requestCounts.access).toBe(2)

  await page.goto('/key-matters-meeting')
  await expect(page).toHaveURL('/')
  await expect(page.getByRole('link', { name: '大事儿管理' })).toHaveCount(0)
  await expect.poll(() => requestCounts.access).toBe(3)
})

test('畸形字符串权限按拒绝处理且直达台账返回首页', async ({ page }) => {
  await mockSession(
    page,
    { id: 9, username: 'malformed', realName: '畸形权限用户', role: 'FULL_STACK_ENGINEER' },
    { canAccess: 'false', canManageAll: false, canFeedbackOwn: false }
  )

  await page.goto('/key-matters')

  await expect(page).toHaveURL('/')
  await expect(page.getByRole('link', { name: '大事儿管理' })).toHaveCount(0)
})

test('空权限响应按拒绝处理且不会产生未捕获导航错误', async ({ page }) => {
  const pageErrors: Error[] = []
  page.on('pageerror', error => pageErrors.push(error))
  await mockSession(
    page,
    { id: 9, username: 'null-access', realName: '空权限用户', role: 'FULL_STACK_ENGINEER' },
    null
  )

  await page.goto('/key-matters')

  await expect(page).toHaveURL('/')
  expect(pageErrors).toEqual([])
})

test('退出登录后忽略仍在请求中的权限成功响应', async ({ page }) => {
  let accessRequestCount = 0
  let markFirstAccessStarted = () => {}
  let releaseFirstAccessResponse = () => {}
  let markFirstAccessSettled = () => {}
  const firstAccessStarted = new Promise<void>(resolve => {
    markFirstAccessStarted = resolve
  })
  const firstAccessRelease = new Promise<void>(resolve => {
    releaseFirstAccessResponse = resolve
  })
  const firstAccessSettled = new Promise<void>(resolve => {
    markFirstAccessSettled = resolve
  })

  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify({
      id: 8,
      username: 'participant',
      realName: '参与人',
      role: 'FULL_STACK_ENGINEER'
    }))
  })

  await page.route('**/api/key-matters/access', async route => {
    accessRequestCount += 1

    if (accessRequestCount === 1) {
      markFirstAccessStarted()
      await firstAccessRelease
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'success',
          data: { canAccess: true, canManageAll: false, canFeedbackOwn: false },
          timestamp: '2026-03-20T00:00:00.000Z'
        })
      })
      markFirstAccessSettled()
      return
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: { canAccess: false, canManageAll: false, canFeedbackOwn: false },
        timestamp: '2026-03-20T00:00:00.000Z'
      })
    })
  })

  await page.goto('/')
  await firstAccessStarted
  await page.getByRole('button', { name: '退出登录' }).click()

  await expect(page).toHaveURL('/login')
  await expect.poll(() => page.evaluate(() => ({
    token: localStorage.getItem('token'),
    user: localStorage.getItem('user')
  }))).toEqual({ token: null, user: null })

  releaseFirstAccessResponse()
  await firstAccessSettled

  await page.evaluate(() => {
    localStorage.setItem('token', 'unrelated-token')
    localStorage.setItem('user', JSON.stringify({
      id: 9,
      username: 'unrelated',
      realName: '无关用户',
      role: 'FULL_STACK_ENGINEER'
    }))
  })
  await page.goBack()

  await expect(page).toHaveURL('/')
  await expect.poll(() => accessRequestCount).toBe(2)
  await expect(page.getByRole('link', { name: '大事儿管理' })).toHaveCount(0)
  expect(accessRequestCount).toBe(2)
})
