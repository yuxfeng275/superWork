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

const featureUsers = [
  { id: 7, username: 'owner-seven', realName: '负责人甲', role: 'FULL_STACK_ENGINEER', status: 1 },
  { id: 16, username: 'owner-sixteen', realName: '负责人乙', role: 'PRODUCT_MANAGER', status: 1 },
  { id: 8, username: 'participant', realName: '参与用户', role: 'FULL_STACK_ENGINEER', status: 1 }
]

const featureProjects = [
  { id: 31, name: '客户体验平台', parentId: null },
  { id: 32, name: '数据治理平台', parentId: null }
]

function featureMatter(options: {
  id: number
  title: string
  ownerId: number
  projectId: number
  currentWeekUpdated?: boolean
}) {
  const owner = featureUsers.find(user => user.id === options.ownerId)!
  const participant = featureUsers.find(user => user.id === 8)!
  const historicalUpdate = {
    id: options.id * 10,
    weekStartDate: '2026-03-09',
    status: '推进中',
    progress: 30,
    progressSummary: `${options.title}上周已完成方案评审`,
    issues: '',
    nextWeekPlan: '继续推进',
    supportNeeded: ''
  }
  const currentWeekUpdate = options.currentWeekUpdated
    ? {
        ...historicalUpdate,
        id: options.id * 10 + 1,
        weekStartDate: '2026-03-16',
        progress: 45,
        progressSummary: `${options.title}本周完成联调`
      }
    : undefined

  return {
    id: options.id,
    title: options.title,
    description: `${options.title}的事项说明`,
    projectId: options.projectId,
    projectName: featureProjects.find(project => project.id === options.projectId)?.name,
    ownerId: options.ownerId,
    ownerName: owner.realName,
    participants: [
      { userId: owner.id, username: owner.username, realName: owner.realName },
      { userId: participant.id, username: participant.username, realName: participant.realName }
    ],
    priority: options.id % 2 ? 'P0' : 'P1',
    status: '推进中',
    progress: currentWeekUpdate?.progress ?? 30,
    startDate: '2026-03-02',
    plannedCompletionDate: '2026-04-30',
    sortOrder: options.id,
    overdue: false,
    currentWeekUpdated: Boolean(currentWeekUpdate),
    latestUpdate: currentWeekUpdate || historicalUpdate,
    currentWeekUpdate: currentWeekUpdate || null,
    weeklyUpdates: currentWeekUpdate ? [currentWeekUpdate, historicalUpdate] : [historicalUpdate]
  }
}

interface FeatureSessionOptions {
  user: TestUser
  access: KeyMatterAccessFixture | (() => KeyMatterAccessFixture)
  matters: ReturnType<typeof featureMatter>[]
  onMatterListGet?: (requestCount: number) => { status: number; body: unknown; delayMs?: number } | undefined
  onMatterPut?: (matterId: number) => { status: number; body: unknown } | undefined
  onWeeklyPut?: (matterId: number) => { status: number; body: unknown } | undefined
}

async function mockFeatureSession(page: Page, options: FeatureSessionOptions) {
  const requestCounts = { access: 0, matterListGet: 0, matterPut: 0, weeklyPut: 0 }

  await page.clock.setFixedTime(new Date('2026-03-20T09:00:00+08:00'))
  await page.addInitScript(({ currentUser }) => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify(currentUser))
  }, { currentUser: options.user })

  await page.route('**/api/requirements**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: { records: [], total: 0 } })
  }))
  await page.route('**/api/projects**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: { records: featureProjects, total: featureProjects.length } })
  }))
  await page.route('**/api/users**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: { records: featureUsers, total: featureUsers.length } })
  }))
  await page.route('**/api/key-matters**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/key-matters/access') {
      requestCounts.access += 1
      const access = typeof options.access === 'function' ? options.access() : options.access
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: access })
      })
      return
    }

    const weeklyMatch = path.match(/^\/api\/key-matters\/(\d+)\/weekly-updates\//)
    if (request.method() === 'PUT' && weeklyMatch) {
      requestCounts.weeklyPut += 1
      const response = options.onWeeklyPut?.(Number(weeklyMatch[1]))
      await route.fulfill({
        status: response?.status ?? 200,
        contentType: 'application/json',
        body: JSON.stringify(response?.body ?? { code: 200, data: request.postDataJSON() })
      })
      return
    }

    const detailMatch = path.match(/^\/api\/key-matters\/(\d+)$/)
    if (request.method() === 'PUT' && detailMatch) {
      requestCounts.matterPut += 1
      const response = options.onMatterPut?.(Number(detailMatch[1]))
      await route.fulfill({
        status: response?.status ?? 200,
        contentType: 'application/json',
        body: JSON.stringify(response?.body ?? { code: 200, data: request.postDataJSON() })
      })
      return
    }

    if (request.method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: request.postDataJSON() })
      })
      return
    }

    if (detailMatch) {
      const matter = options.matters.find(item => item.id === Number(detailMatch[1]))
      await route.fulfill({
        status: matter ? 200 : 404,
        contentType: 'application/json',
        body: JSON.stringify(matter
          ? { code: 200, data: matter }
          : { code: 404, message: '事项不存在' })
      })
      return
    }

    if (request.method() === 'GET' && path === '/api/key-matters') {
      requestCounts.matterListGet += 1
      const response = options.onMatterListGet?.(requestCounts.matterListGet)
      if (response) {
        if (response.delayMs) await new Promise(resolve => setTimeout(resolve, response.delayMs))
        await route.fulfill({
          status: response.status,
          contentType: 'application/json',
          body: JSON.stringify(response.body)
        })
        return
      }
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: options.matters })
    })
  })

  return requestCounts
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

test('管理员可以维护参与人且负责人自动保留', async ({ page }) => {
  const admin = { id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' }
  const matter = featureMatter({ id: 41, title: '管理员维护参与人事项', ownerId: 7, projectId: 31 })
  matter.participants = [matter.participants[0]]
  await mockFeatureSession(page, {
    user: admin,
    access: { canAccess: true, canManageAll: true, canFeedbackOwn: true },
    matters: [matter]
  })

  await page.goto('/key-matters')
  const row = page.getByLabel('大事儿列表').locator('tr', { hasText: matter.title })
  await row.getByRole('button', { name: '编辑事项' }).click()

  const dialog = page.getByRole('dialog', { name: '编辑大事儿' })
  const participants = dialog.getByLabel('参与人')
  await expect(participants).toBeVisible()
  await expect(dialog.locator('.el-form-item', { hasText: '参与人' })).toContainText('负责人甲')

  await participants.press('ArrowDown')
  await page.getByRole('option', { name: '参与用户' }).click()
  await page.keyboard.press('Escape')
  const owner = dialog.getByLabel('负责人')
  await owner.fill('负责人乙')
  await owner.press('ArrowDown')
  await owner.press('Enter')

  const participantField = dialog.locator('.el-form-item', { hasText: '参与人' })
  await expect(participantField).toContainText('负责人甲')
  await expect(participantField).toContainText('参与用户')
  await expect(participantField).toContainText('+ 1')

  const requestPromise = page.waitForRequest(request =>
    new URL(request.url()).pathname === '/api/key-matters/41' && request.method() === 'PUT'
  )
  await dialog.getByRole('button', { name: '保存' }).click()
  const payload = (await requestPromise).postDataJSON()
  expect(payload.ownerId).toBe(16)
  expect(new Set(payload.participantIds)).toEqual(new Set([7, 8, 16]))
  expect(payload.participantIds).toHaveLength(3)
})

test('负责人只能反馈本人事项', async ({ page }) => {
  const ownMatter = featureMatter({ id: 41, title: '本人负责事项', ownerId: 7, projectId: 31 })
  const otherMatter = featureMatter({ id: 42, title: '他人负责事项', ownerId: 16, projectId: 32 })
  await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true },
    matters: [ownMatter, otherMatter]
  })

  await page.goto('/key-matters')
  const table = page.getByLabel('大事儿列表')
  const ownRow = table.locator('tr', { hasText: ownMatter.title })
  const otherRow = table.locator('tr', { hasText: otherMatter.title })
  await expect(ownRow.getByRole('button', { name: '更新周进展' })).toBeVisible()
  await expect(otherRow.getByRole('button', { name: '更新周进展' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新增事项' })).toHaveCount(0)
  await expect(table.getByRole('button', { name: '编辑事项' })).toHaveCount(0)
  await expect(table.getByRole('button', { name: '删除事项' })).toHaveCount(0)

  await ownRow.getByText(ownMatter.title).click()
  let detail = page.locator('.detail-content')
  await expect(detail.getByRole('button', { name: '更新周进展' })).toBeVisible()
  await expect(detail.getByLabel('周进展记录').getByRole('button', { name: '编辑周进展' })).toBeVisible()
  await expect(detail.getByLabel('周进展记录').getByRole('button', { name: '删除周进展' })).toBeVisible()
  await page.getByRole('button', { name: '关闭大事儿详情' }).click()

  await otherRow.getByText(otherMatter.title).click()
  detail = page.locator('.detail-content')
  await expect(detail.getByRole('button', { name: '更新周进展' })).toHaveCount(0)
  await expect(detail.getByLabel('周进展记录').getByRole('button', { name: '编辑周进展' })).toHaveCount(0)
  await expect(detail.getByLabel('周进展记录').getByRole('button', { name: '删除周进展' })).toHaveCount(0)

  await page.goto('/key-matters-meeting')
  const stage = page.getByLabel('周会演示模式')
  await expect(stage.getByRole('heading', { name: ownMatter.title })).toBeVisible()
  await expect(stage.getByLabel('演示中更新周报')).toBeVisible()
  await expect(stage.getByRole('button', { name: /保存并下一项/ })).toBeVisible()
  await stage.getByRole('button', { name: '跳转到第 2 项' }).click()
  await expect(stage.getByRole('heading', { name: otherMatter.title })).toBeVisible()
  await expect(stage.getByLabel('待负责人反馈', { exact: true })).toBeVisible()
  await expect(stage.getByLabel('演示中更新周报')).toHaveCount(0)
  await expect(stage.getByRole('button', { name: /保存并下一项/ })).toHaveCount(0)
  await expect(stage.getByRole('button', { name: '查看详情' })).toBeVisible()
})

test('普通参与人只能查看所有大事儿', async ({ page }) => {
  const firstMatter = featureMatter({ id: 41, title: '参与人可见事项一', ownerId: 7, projectId: 31 })
  const secondMatter = featureMatter({ id: 42, title: '参与人可见事项二', ownerId: 16, projectId: 32 })
  await mockFeatureSession(page, {
    user: featureUsers[2],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: false },
    matters: [firstMatter, secondMatter]
  })

  await page.goto('/key-matters')
  const table = page.getByLabel('大事儿列表')
  const firstRow = table.locator('tr', { hasText: firstMatter.title })
  const secondRow = table.locator('tr', { hasText: secondMatter.title })
  await expect(firstRow.getByText(firstMatter.title)).toBeVisible()
  await expect(secondRow.getByText(secondMatter.title)).toBeVisible()
  await expect(firstRow.getByText('待负责人反馈', { exact: true })).toBeVisible()
  await expect(firstRow.getByText('本周待更新', { exact: true })).toHaveCount(0)
  await expect(secondRow.getByText('待负责人反馈', { exact: true })).toBeVisible()
  await expect(secondRow.getByText('本周待更新', { exact: true })).toHaveCount(0)
  await expect(table.getByLabel(`${firstMatter.title}参与人`)).toContainText('负责人甲')
  await expect(table.getByLabel(`${firstMatter.title}参与人`)).toContainText('参与用户')
  await expect(table.getByLabel(`${secondMatter.title}参与人`)).toContainText('负责人乙')
  await expect(table.getByLabel(`${secondMatter.title}参与人`)).toContainText('参与用户')
  await expect(page.getByRole('button', { name: '新增事项' })).toHaveCount(0)
  await expect(table.getByRole('button', { name: '更新周进展' })).toHaveCount(0)
  await expect(table.getByRole('button', { name: '编辑事项' })).toHaveCount(0)
  await expect(table.getByRole('button', { name: '删除事项' })).toHaveCount(0)

  await table.getByText(firstMatter.title).click()
  const detail = page.locator('.detail-content')
  await expect(detail.getByLabel('事项参与人')).toContainText('负责人甲')
  await expect(detail.getByLabel('事项参与人')).toContainText('参与用户')
  await expect(detail.getByRole('button', { name: '更新周进展' })).toHaveCount(0)
  await expect(detail.getByRole('button', { name: '编辑周进展' })).toHaveCount(0)
  await expect(detail.getByRole('button', { name: '删除周进展' })).toHaveCount(0)

  await page.goto('/key-matters-meeting')
  const stage = page.getByLabel('周会演示模式')
  const meetingHeader = stage.locator('.presentation-card-header')
  const meetingBody = stage.getByLabel('待负责人反馈', { exact: true })
  await expect(meetingHeader.getByText('待负责人反馈', { exact: true })).toBeVisible()
  await expect(meetingHeader.getByText('本周待更新', { exact: true })).toHaveCount(0)
  await expect(meetingBody).toBeVisible()
  await expect(meetingBody.getByText('本周待更新', { exact: true })).toHaveCount(0)
  await expect(stage.getByLabel('演示中更新周报')).toHaveCount(0)
  await expect(stage.getByRole('button', { name: '编辑周报' })).toHaveCount(0)
  await expect(stage.getByRole('button', { name: /保存并下一项/ })).toHaveCount(0)
})

test('负责人变更后保存周进度刷新为只读', async ({ page }) => {
  const matter = featureMatter({ id: 41, title: '负责人变更事项', ownerId: 7, projectId: 31 })
  let ownerChanged = false
  const requestCounts = await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true },
    matters: [matter],
    onWeeklyPut: matterId => {
      if (matterId !== matter.id) return undefined
      ownerChanged = true
      const nextOwner = featureUsers[1]
      Object.assign(matter, {
        ownerId: nextOwner.id,
        ownerName: nextOwner.realName,
        participants: [
          ...matter.participants,
          { userId: nextOwner.id, username: nextOwner.username, realName: nextOwner.realName }
        ]
      })
      return {
        status: 403,
        body: { code: 403, message: '仅事项负责人可反馈周进度' }
      }
    }
  })

  await page.goto('/key-matters')
  const table = page.getByLabel('大事儿列表')
  const row = table.locator('tr', { hasText: matter.title })
  await row.getByRole('button', { name: '更新周进展' }).click()
  const weekly = page.getByRole('dialog', { name: '更新周进展' })
  await weekly.getByRole('textbox', { name: '本周成果' }).fill('负责人变更前填写的进展')
  await weekly.getByRole('button', { name: '保存周进展' }).click()

  await expect.poll(() => ownerChanged).toBe(true)
  await expect(weekly).toHaveCount(0)
  await expect(page.getByText('仅事项负责人可反馈周进度')).toBeVisible()
  await expect(row).toContainText('负责人乙')
  await expect(row.getByRole('button', { name: '更新周进展' })).toHaveCount(0)
  await expect.poll(() => requestCounts.access).toBeGreaterThanOrEqual(2)

  await row.getByText(matter.title).click()
  const detail = page.locator('.detail-content')
  await expect(detail).toContainText(matter.title)
  await expect(detail.getByRole('button', { name: '更新周进展' })).toHaveCount(0)
})

test('参与关系被移除后读取403自动退出大事儿且忽略延迟重复错误', async ({ page }) => {
  const matter = featureMatter({ id: 41, title: '参与关系撤销事项', ownerId: 7, projectId: 31 })
  let accessRevoked = false
  await page.addInitScript(() => {
    let deniedToastCount = 0
    const seen = new WeakSet<Element>()
    const countDeniedToasts = () => {
      document.querySelectorAll('.el-message__content').forEach(element => {
        if (element.textContent?.trim() === '无权访问大事儿' && !seen.has(element)) {
          seen.add(element)
          deniedToastCount += 1
        }
      })
    }
    new MutationObserver(countDeniedToasts).observe(document, {
      childList: true,
      subtree: true
    })
    Object.defineProperty(window, '__deniedToastCount', {
      configurable: true,
      get: () => deniedToastCount
    })
  })
  const requestCounts = await mockFeatureSession(page, {
    user: featureUsers[2],
    access: () => accessRevoked
      ? { canAccess: false, canManageAll: false, canFeedbackOwn: false }
      : { canAccess: true, canManageAll: false, canFeedbackOwn: false },
    matters: [matter],
    onMatterListGet: requestCount => {
      accessRevoked = true
      return {
        status: 403,
        body: { code: 403, message: '无权访问大事儿' },
        delayMs: requestCount === 2 ? 700 : undefined
      }
    }
  })

  await page.goto('/key-matters')

  await expect(page).toHaveURL('/')
  await page.waitForTimeout(800)
  await expect.poll(() => requestCounts.matterListGet).toBe(2)
  await expect.poll(() => requestCounts.access).toBe(2)
  await expect.poll(() => page.evaluate(() => (window as Window & { __deniedToastCount: number }).__deniedToastCount)).toBe(1)
  await expect(page.getByRole('link', { name: '大事儿管理' })).toHaveCount(0)
})

test('管理员权限撤销后保存事项刷新为只读', async ({ page }) => {
  const matter = featureMatter({ id: 41, title: '管理员权限撤销事项', ownerId: 7, projectId: 31 })
  let manageRevoked = false
  const requestCounts = await mockFeatureSession(page, {
    user: { id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' },
    access: () => manageRevoked
      ? { canAccess: true, canManageAll: false, canFeedbackOwn: false }
      : { canAccess: true, canManageAll: true, canFeedbackOwn: true },
    matters: [matter],
    onMatterPut: matterId => {
      if (matterId !== matter.id) return undefined
      manageRevoked = true
      return {
        status: 403,
        body: { code: 403, message: '仅管理员可管理大事儿' }
      }
    }
  })

  await page.goto('/key-matters')
  const table = page.getByLabel('大事儿列表')
  const row = table.locator('tr', { hasText: matter.title })
  await row.getByRole('button', { name: '编辑事项' }).click()
  const dialog = page.getByRole('dialog', { name: '编辑大事儿' })
  await dialog.getByRole('button', { name: '保存' }).click()

  await expect.poll(() => requestCounts.matterPut).toBe(1)
  await expect(page.getByText('仅管理员可管理大事儿', { exact: true })).toBeVisible()
  await expect(dialog).toHaveCount(0)
  await expect.poll(() => requestCounts.access).toBeGreaterThanOrEqual(2)
  await expect(page).toHaveURL('/key-matters')
  await expect(row.getByText(matter.title)).toBeVisible()
  await expect(page.getByRole('button', { name: '新增事项' })).toHaveCount(0)
  await expect(table.getByRole('button', { name: '编辑事项' })).toHaveCount(0)
  await expect(table.getByRole('button', { name: '删除事项' })).toHaveCount(0)
  expect(requestCounts.matterListGet).toBeGreaterThanOrEqual(4)
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
