import { expect, test, type Page, type Response } from '@playwright/test'

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
  canCreateOwn?: boolean
}

function fixtureAccess(access: Partial<KeyMatterAccessFixture> | null | undefined): KeyMatterAccessFixture {
  return {
    canAccess: access?.canAccess === true,
    canManageAll: access?.canManageAll === true,
    canFeedbackOwn: access?.canFeedbackOwn === true,
    canCreateOwn: access?.canCreateOwn === true
  }
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
  participantIds?: number[]
}) {
  const owner = featureUsers.find(user => user.id === options.ownerId)!
  const participantIds = options.participantIds ?? [owner.id, 8]
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
    participants: Array.from(new Set([owner.id, ...participantIds])).map(userId => {
      const user = featureUsers.find(candidate => candidate.id === userId)!
      return { userId: user.id, username: user.username, realName: user.realName }
    }),
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

type FeatureMatterFixture = Omit<ReturnType<typeof featureMatter>, 'participants'> & {
  participants?: ReturnType<typeof featureMatter>['participants']
}

interface FeatureSessionOptions {
  user: TestUser
  access: KeyMatterAccessFixture | (() => KeyMatterAccessFixture)
  matters: FeatureMatterFixture[]
  onAccessGet?: (requestCount: number) => { access: KeyMatterAccessFixture; waitUntil?: Promise<void> } | undefined
  onMatterListGet?: (requestCount: number) => { status: number; body: unknown; delayMs?: number; waitUntil?: Promise<void> } | undefined
  onMatterPost?: (payload: unknown) => { status?: number; body?: unknown } | undefined
  onMatterPut?: (matterId: number) => { status: number; body: unknown } | undefined
}

async function mockFeatureSession(page: Page, options: FeatureSessionOptions) {
  const requestCounts = {
    access: 0,
    matterListGet: 0,
    matterListUrls: [] as string[],
    matterPut: 0,
    weeklyPut: 0
  }

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
      const response = options.onAccessGet?.(requestCounts.access)
      if (response?.waitUntil) await response.waitUntil
      const access = fixtureAccess(response?.access ?? (typeof options.access === 'function' ? options.access() : options.access))
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
      const response = options.onMatterPost?.(request.postDataJSON())
      await route.fulfill({
        status: response?.status ?? 200,
        contentType: 'application/json',
        body: JSON.stringify(response?.body ?? { code: 200, data: request.postDataJSON() })
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
      requestCounts.matterListUrls.push(request.url())
      const response = options.onMatterListGet?.(requestCounts.matterListGet)
      if (response) {
        if (response.delayMs) await new Promise(resolve => setTimeout(resolve, response.delayMs))
        if (response.waitUntil) await response.waitUntil
        await route.fulfill({
          status: response.status,
          contentType: 'application/json',
          body: JSON.stringify(response.body)
        })
        return
      }
    }

    const params = new URL(request.url()).searchParams
    const keyword = params.get('keyword')
    const status = params.get('status')
    const priority = params.get('priority')
    const ownerId = params.get('ownerId')
    const projectId = params.get('projectId')
    const records = options.matters.filter(matter =>
      (!keyword || matter.title.includes(keyword) || matter.description.includes(keyword))
      && (!status || matter.status === status)
      && (!priority || matter.priority === priority)
      && (!ownerId || String(matter.ownerId) === ownerId)
      && (!projectId || String(matter.projectId) === projectId)
    )
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: records })
    })
  })

  return requestCounts
}

function waitForCompletedMatterListResponses(page: Page, expectedCount: number) {
  return new Promise<void>(resolve => {
    let completedCount = 0
    const handleResponse = async (response: Response) => {
      const request = response.request()
      if (request.method() !== 'GET' || new URL(response.url()).pathname !== '/api/key-matters') return

      await response.finished()
      completedCount += 1
      if (completedCount >= expectedCount) {
        page.off('response', handleResponse)
        resolve()
      }
    }
    page.on('response', handleResponse)
  })
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
          data: fixtureAccess(access),
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
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true, canCreateOwn: true }
  },
  {
    label: '仅参与人',
    user: { id: 8, username: 'participant', realName: '参与人', role: 'FULL_STACK_ENGINEER' },
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: false, canCreateOwn: true }
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
    await expect.poll(() => requestCounts.access).toBeGreaterThanOrEqual(1)
    await expect.poll(() => requestCounts.requirements).toBe(1)

    await page.goto('/key-matters-meeting')

    await expect(page).toHaveURL('/key-matters-meeting')
    await expect(page.locator('.layout')).toHaveCount(0)
    await expect(page.getByRole('link', { name: '大事儿管理' })).toHaveCount(0)
    await expect.poll(() => requestCounts.access).toBeGreaterThanOrEqual(2)
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

test('个人快捷筛选区分本人负责和仅参与事项', async ({ page }) => {
  const ownedMatter = featureMatter({
    id: 51,
    title: '本人负责的筛选事项',
    ownerId: 7,
    projectId: 31,
    participantIds: [7, 8]
  })
  const participatingMatter = featureMatter({
    id: 52,
    title: '本人仅参与的筛选事项',
    ownerId: 16,
    projectId: 31,
    participantIds: [16, 7]
  })
  const unrelatedMatter = featureMatter({
    id: 53,
    title: '本人无关的筛选事项',
    ownerId: 16,
    projectId: 32,
    participantIds: [16, 8]
  })
  const legacyNonOwnedMatter: FeatureMatterFixture = featureMatter({
    id: 54,
    title: '旧数据缺少参与人的他人事项',
    ownerId: 16,
    projectId: 32
  })
  delete legacyNonOwnedMatter.participants
  const legacyOwnedMatter: FeatureMatterFixture = featureMatter({
    id: 55,
    title: '旧数据缺少参与人的本人事项',
    ownerId: 7,
    projectId: 31
  })
  delete legacyOwnedMatter.participants
  await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true },
    matters: [ownedMatter, participatingMatter, unrelatedMatter, legacyNonOwnedMatter, legacyOwnedMatter]
  })

  await page.goto('/key-matters')
  const rail = page.getByRole('complementary', { name: '列表快速筛选' })
  const table = page.getByLabel('大事儿列表')
  const allButton = rail.getByRole('button', { name: '全部事项' })
  const ownedButton = rail.getByRole('button', { name: /我的事项/ })
  const participatingButton = rail.getByRole('button', { name: /我参与的事项/ })

  await expect(page.locator('.load-error')).toHaveCount(0)
  await expect(ownedButton).toBeVisible()
  await expect(ownedButton).toContainText('2 项由我负责')
  await expect(participatingButton).toBeVisible()
  await expect(participatingButton).toContainText('1 项协作参与')
  await expect(ownedButton).toHaveAttribute('aria-pressed', 'false')
  await expect(participatingButton).toHaveAttribute('aria-pressed', 'false')
  await expect(allButton).toHaveClass(/\bactive\b/)

  await ownedButton.click()
  await expect(ownedButton).toHaveClass(/\bactive\b/)
  await expect(ownedButton).toHaveAttribute('aria-pressed', 'true')
  await expect(participatingButton).not.toHaveClass(/\bactive\b/)
  await expect(participatingButton).toHaveAttribute('aria-pressed', 'false')
  await expect(allButton).not.toHaveClass(/\bactive\b/)
  await expect(table.getByText(ownedMatter.title)).toBeVisible()
  await expect(table.getByText(legacyOwnedMatter.title)).toBeVisible()
  await expect(table.getByText(participatingMatter.title)).toHaveCount(0)
  await expect(table.getByText(unrelatedMatter.title)).toHaveCount(0)
  await expect(table.getByText(legacyNonOwnedMatter.title)).toHaveCount(0)

  await ownedButton.click()
  await expect(ownedButton).toHaveClass(/\bactive\b/)
  await expect(table.getByText(ownedMatter.title)).toBeVisible()
  await expect(table.getByText(legacyOwnedMatter.title)).toBeVisible()
  await expect(table.getByText(participatingMatter.title)).toHaveCount(0)
  await expect(table.getByText(unrelatedMatter.title)).toHaveCount(0)
  await expect(table.getByText(legacyNonOwnedMatter.title)).toHaveCount(0)

  await participatingButton.click()
  await expect(participatingButton).toHaveClass(/\bactive\b/)
  await expect(participatingButton).toHaveAttribute('aria-pressed', 'true')
  await expect(ownedButton).not.toHaveClass(/\bactive\b/)
  await expect(ownedButton).toHaveAttribute('aria-pressed', 'false')
  await expect(allButton).not.toHaveClass(/\bactive\b/)
  await expect(table.getByText(participatingMatter.title)).toBeVisible()
  await expect(table.getByText(ownedMatter.title)).toHaveCount(0)
  await expect(table.getByText(legacyOwnedMatter.title)).toHaveCount(0)
  await expect(table.getByText(unrelatedMatter.title)).toHaveCount(0)
  await expect(table.getByText(legacyNonOwnedMatter.title)).toHaveCount(0)

  await allButton.click()
  await expect(allButton).toHaveClass(/\bactive\b/)
  await expect(ownedButton).not.toHaveClass(/\bactive\b/)
  await expect(ownedButton).toHaveAttribute('aria-pressed', 'false')
  await expect(participatingButton).not.toHaveClass(/\bactive\b/)
  await expect(participatingButton).toHaveAttribute('aria-pressed', 'false')
  await expect(table.getByText(ownedMatter.title)).toBeVisible()
  await expect(table.getByText(participatingMatter.title)).toBeVisible()
  await expect(table.getByText(unrelatedMatter.title)).toBeVisible()
  await expect(table.getByText(legacyNonOwnedMatter.title)).toBeVisible()
  await expect(table.getByText(legacyOwnedMatter.title)).toBeVisible()
})

test('个人快捷筛选清空普通筛选并保持完整数量、概览和分组计数', async ({ page }) => {
  const ownedMatters = Array.from({ length: 11 }, (_, index) => featureMatter({
    id: 100 + index,
    title: `分页本人负责事项 ${index + 1}`,
    ownerId: 7,
    projectId: index % 2 === 0 ? 31 : 32,
    participantIds: [7, 8]
  }))
  ownedMatters[0].status = '有风险'
  const participatingMatters = Array.from({ length: 11 }, (_, index) => {
    const ownerId = index % 2 === 0 ? 16 : 8
    return featureMatter({
      id: 200 + index,
      title: `分页本人参与事项 ${index + 1}`,
      ownerId,
      projectId: index % 3 === 0 ? 32 : 31,
      participantIds: [ownerId, 7]
    })
  })
  const unrelatedMatter = featureMatter({
    id: 300,
    title: '分页无关事项',
    ownerId: 16,
    projectId: 32,
    participantIds: [16, 8]
  })
  await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true },
    matters: [...ownedMatters, ...participatingMatters, unrelatedMatter]
  })

  await page.goto('/key-matters')
  const rail = page.getByRole('complementary', { name: '列表快速筛选' })
  const filterBar = page.getByLabel('事项筛选')
  const table = page.getByLabel('大事儿列表')
  const pagination = page.getByLabel('事项列表分页')
  const overview = page.getByLabel('大事儿操作栏').getByLabel('事项概览')
  const allButton = rail.getByRole('button', { name: '全部事项' })
  const ownedButton = rail.getByRole('button', { name: /我的事项/ })
  const participatingButton = rail.getByRole('button', { name: /我参与的事项/ })
  const project31Button = rail.locator('[aria-label="按项目筛选"]')
    .getByRole('button', { name: /客户体验平台/ })
  const owner16Button = rail.locator('[aria-label="按负责人筛选"]')
    .getByRole('button', { name: /负责人乙/ })
  const owner7Button = rail.locator('[aria-label="按负责人筛选"]')
    .getByRole('button', { name: /负责人甲/ })
  const owner8Button = rail.locator('[aria-label="按负责人筛选"]')
    .getByRole('button', { name: /参与用户/ })
  const project32Button = rail.locator('[aria-label="按项目筛选"]')
    .getByRole('button', { name: /数据治理平台/ })
  const keywordControl = filterBar.getByPlaceholder('搜索标题或说明')
  const prioritySelect = filterBar.getByRole('combobox', { name: '优先级' })
  const statusSelect = filterBar.getByRole('combobox', { name: '状态' })
  const ownerControl = filterBar.getByRole('combobox', { name: '负责人' })
  const projectControl = filterBar.getByRole('combobox', { name: '关联项目' })

  await expect(prioritySelect).toBeVisible()
  await expect(statusSelect).toBeVisible()
  await expect(ownerControl).toBeVisible()
  await expect(projectControl).toBeVisible()

  const expectFixedPersonalCounts = async () => {
    await expect(ownedButton).toContainText('11 项由我负责')
    await expect(participatingButton).toContainText('11 项协作参与')
    await expect(project31Button).toContainText('13 项')
    await expect(project32Button).toContainText('10 项')
    await expect(owner7Button).toContainText('11 项')
    await expect(owner16Button).toContainText('7 项')
    await expect(owner8Button).toContainText('5 项')
  }
  const expectOwnedRecords = async () => {
    await expect(ownedButton).toHaveClass(/\bactive\b/)
    await expect(pagination).toContainText('共 11 项')
    for (const matter of ownedMatters) {
      await expect(table.getByText(matter.title, { exact: true })).toBeVisible()
    }
    await expect(table.getByText(participatingMatters[0].title, { exact: true })).toHaveCount(0)
    await expect(table.getByText(unrelatedMatter.title, { exact: true })).toHaveCount(0)
  }
  const expectParticipatingRecords = async () => {
    await expect(participatingButton).toHaveClass(/\bactive\b/)
    await expect(pagination).toContainText('共 11 项')
    for (const matter of participatingMatters) {
      await expect(table.getByText(matter.title, { exact: true })).toBeVisible()
    }
    await expect(table.getByText(ownedMatters[0].title, { exact: true })).toHaveCount(0)
    await expect(table.getByText(unrelatedMatter.title, { exact: true })).toHaveCount(0)
  }

  await expectFixedPersonalCounts()
  await expect(overview.locator('.summary-cell.all .summary-value strong')).toHaveText('23')
  await expect(overview.locator('.summary-cell.risk .summary-value strong')).toHaveText('1')
  await pagination.locator('.el-select').click()
  await page.getByRole('option', { name: '20条/页' }).click()

  await ownedButton.click()
  await expectOwnedRecords()
  await expect(overview.locator('.summary-cell.all .summary-value strong')).toHaveText('23')
  await expect(overview.locator('.summary-cell.risk .summary-value strong')).toHaveText('1')

  await project31Button.click()
  await expect(project31Button).toHaveClass(/\bactive\b/)
  await expect(ownedButton).not.toHaveClass(/\bactive\b/)
  await expect(pagination).toContainText('共 13 项')
  await expectFixedPersonalCounts()
  await ownedButton.click()
  await expect(project31Button).not.toHaveClass(/\bactive\b/)
  await expectOwnedRecords()

  await owner16Button.click()
  await expect(owner16Button).toHaveClass(/\bactive\b/)
  await expect(participatingButton).not.toHaveClass(/\bactive\b/)
  await expect(pagination).toContainText('共 7 项')
  await expectFixedPersonalCounts()
  await participatingButton.click()
  await expect(owner16Button).not.toHaveClass(/\bactive\b/)
  await expectParticipatingRecords()

  await keywordControl.fill('分页本人负责事项 1')
  await filterBar.getByRole('button', { name: '查询' }).click()
  await expect(participatingButton).not.toHaveClass(/\bactive\b/)
  await expect(keywordControl).toHaveValue('分页本人负责事项 1')
  await expect(pagination).toContainText('共 3 项')
  await expectFixedPersonalCounts()
  await ownedButton.click()
  await expect(keywordControl).toHaveValue('')
  await expectOwnedRecords()

  await statusSelect.press('ArrowDown')
  await page.getByRole('option', { name: '有风险', exact: true }).click()
  await expect(filterBar).toContainText('有风险')
  await filterBar.getByRole('button', { name: '查询' }).click()
  await expect(ownedButton).not.toHaveClass(/\bactive\b/)
  await expect(filterBar).toContainText('有风险')
  await expect(pagination).toContainText('共 1 项')
  await expectFixedPersonalCounts()
  await ownedButton.click()
  await expect(filterBar).not.toContainText('有风险')
  await expect(filterBar).toContainText('状态')
  await expectOwnedRecords()

  await prioritySelect.press('ArrowDown')
  await page.getByRole('option', { name: 'P0', exact: true }).click()
  await expect(filterBar).toContainText('P0')
  await filterBar.getByRole('button', { name: '查询' }).click()
  await expect(ownedButton).not.toHaveClass(/\bactive\b/)
  await expect(filterBar).toContainText('P0')
  await expect(pagination).toContainText('共 10 项')
  await expectFixedPersonalCounts()
  await participatingButton.click()
  await expect(filterBar).not.toContainText('P0')
  await expect(filterBar).toContainText('优先级')
  await expectParticipatingRecords()

  await allButton.click()
  await expect(allButton).toHaveClass(/\bactive\b/)
  await expect(participatingButton).not.toHaveClass(/\bactive\b/)
  await expect(pagination).toContainText('共 23 项')
  await expectFixedPersonalCounts()

  await ownedButton.click()
  await filterBar.getByRole('button', { name: '重置' }).click()
  await expect(ownedButton).not.toHaveClass(/\bactive\b/)
  await expect(allButton).toHaveClass(/\bactive\b/)
  await expect(pagination).toContainText('共 23 项')
  await expectFixedPersonalCounts()
})

test('旧普通筛选响应不会覆盖新的个人筛选', async ({ page }) => {
  const ownedMatters = [
    featureMatter({ id: 320, title: '竞态本人事项一', ownerId: 7, projectId: 31 }),
    featureMatter({ id: 321, title: '竞态本人事项二', ownerId: 7, projectId: 32 })
  ]
  const unrelatedMatter = featureMatter({
    id: 322,
    title: '竞态普通查询命中事项',
    ownerId: 16,
    projectId: 31
  })
  const matters = [...ownedMatters, unrelatedMatter]
  let releaseOrdinaryResponses: () => void = () => undefined
  const ordinaryResponsesReleased = new Promise<void>(resolve => {
    releaseOrdinaryResponses = resolve
  })
  const requestCounts = await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true },
    matters,
    onMatterListGet: requestCount => {
      if (requestCount === 3) {
        return {
          status: 200,
          body: { code: 200, data: [unrelatedMatter] },
          waitUntil: ordinaryResponsesReleased
        }
      }
      if (requestCount === 4) {
        return {
          status: 200,
          body: { code: 200, data: matters },
          waitUntil: ordinaryResponsesReleased
        }
      }
    }
  })

  await page.goto('/key-matters')
  const rail = page.getByRole('complementary', { name: '列表快速筛选' })
  const filterBar = page.getByLabel('事项筛选')
  const table = page.getByLabel('大事儿列表')
  const pagination = page.getByLabel('事项列表分页')
  const ownedButton = rail.getByRole('button', { name: /我的事项/ })

  await expect(table.getByText(ownedMatters[0].title, { exact: true })).toBeVisible()
  await filterBar.getByPlaceholder('搜索标题或说明').fill('竞态普通查询命中事项')
  await filterBar.getByRole('button', { name: '查询' }).click()
  await expect.poll(() => requestCounts.matterListGet).toBe(4)

  await ownedButton.click()
  await expect.poll(() => requestCounts.matterListGet).toBe(5)
  await expect(ownedButton).toHaveClass(/\bactive\b/)
  await expect(pagination).toContainText('共 2 项')
  for (const matter of ownedMatters) {
    await expect(table.getByText(matter.title, { exact: true })).toBeVisible()
  }
  await expect(table.getByText(unrelatedMatter.title, { exact: true })).toHaveCount(0)

  const ordinaryRequests = requestCounts.matterListUrls.slice(2, 4).map(url => new URL(url))
  expect(ordinaryRequests.find(url => url.searchParams.has('keyword'))?.searchParams.get('keyword'))
    .toBe('竞态普通查询命中事项')
  const completeRequest = ordinaryRequests.find(url => !url.searchParams.has('keyword'))
  expect(completeRequest).toBeDefined()
  for (const parameter of ['keyword', 'status', 'priority', 'ownerId', 'projectId']) {
    expect(completeRequest?.searchParams.has(parameter)).toBe(false)
  }
  const personalRequest = new URL(requestCounts.matterListUrls[4])
  for (const parameter of ['keyword', 'status', 'priority', 'ownerId', 'projectId']) {
    expect(personalRequest.searchParams.has(parameter)).toBe(false)
  }

  const staleResponsesCompleted = waitForCompletedMatterListResponses(page, 2)
  releaseOrdinaryResponses()
  await staleResponsesCompleted

  await expect(ownedButton).toHaveClass(/\bactive\b/)
  await expect(pagination).toContainText('共 2 项')
  for (const matter of ownedMatters) {
    await expect(table.getByText(matter.title, { exact: true })).toBeVisible()
  }
  await expect(table.getByText(unrelatedMatter.title, { exact: true })).toHaveCount(0)
})

test('被新个人筛选取代的旧403不提示也不跳转', async ({ page }) => {
  const ownedMatters = [
    featureMatter({ id: 325, title: '权限竞态本人事项一', ownerId: 7, projectId: 31 }),
    featureMatter({ id: 326, title: '权限竞态本人事项二', ownerId: 7, projectId: 32 })
  ]
  const unrelatedMatter = featureMatter({
    id: 327,
    title: '权限竞态普通查询事项',
    ownerId: 16,
    projectId: 31
  })
  let releaseAccessRefresh: () => void = () => undefined
  const accessRefreshReleased = new Promise<void>(resolve => {
    releaseAccessRefresh = resolve
  })
  await page.addInitScript(() => {
    let staleDeniedToastCount = 0
    const seen = new WeakSet<Element>()
    const countStaleDeniedToasts = () => {
      document.querySelectorAll('.el-message__content').forEach(element => {
        if (element.textContent?.trim() === '普通筛选权限已过期' && !seen.has(element)) {
          seen.add(element)
          staleDeniedToastCount += 1
        }
      })
    }
    new MutationObserver(countStaleDeniedToasts).observe(document, { childList: true, subtree: true })
    Object.defineProperty(window, '__staleDeniedToastCount', {
      configurable: true,
      get: () => staleDeniedToastCount
    })
  })
  const requestCounts = await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: true, canFeedbackOwn: true },
    matters: [...ownedMatters, unrelatedMatter],
    onAccessGet: requestCount => requestCount === 2
      ? {
          access: { canAccess: false, canManageAll: false, canFeedbackOwn: false },
          waitUntil: accessRefreshReleased
        }
      : undefined,
    onMatterListGet: requestCount => requestCount === 3
      ? {
          status: 403,
          body: { code: 403, message: '普通筛选权限已过期' }
        }
      : undefined
  })

  await page.goto('/key-matters')
  const rail = page.getByRole('complementary', { name: '列表快速筛选' })
  const filterBar = page.getByLabel('事项筛选')
  const table = page.getByLabel('大事儿列表')
  const ownedButton = rail.getByRole('button', { name: /我的事项/ })

  await expect(table.getByText(ownedMatters[0].title, { exact: true })).toBeVisible()
  await filterBar.getByPlaceholder('搜索标题或说明').fill(unrelatedMatter.title)
  await filterBar.getByRole('button', { name: '查询' }).click()
  await expect.poll(() => requestCounts.access).toBe(2)

  await ownedButton.click()
  await expect.poll(() => requestCounts.matterListGet).toBe(5)
  await expect(ownedButton).toHaveClass(/\bactive\b/)
  for (const matter of ownedMatters) {
    await expect(table.getByText(matter.title, { exact: true })).toBeVisible()
  }
  await expect(table.getByText(unrelatedMatter.title, { exact: true })).toHaveCount(0)

  releaseAccessRefresh()
  await expect.poll(() => requestCounts.access).toBe(2)

  await expect(page).toHaveURL('/key-matters')
  await expect(page.getByRole('link', { name: '大事儿管理' })).toBeVisible()
  await expect(ownedButton).toHaveClass(/\bactive\b/)
  await expect(ownedButton).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('button', { name: '新增事项' })).toBeVisible()
  for (const matter of ownedMatters) {
    const row = table.locator('tr', { hasText: matter.title })
    await expect(table.getByText(matter.title, { exact: true })).toBeVisible()
    await expect(row.getByRole('button', { name: '编辑事项' })).toBeVisible()
    await expect(row.getByRole('button', { name: '删除事项' })).toBeVisible()
  }
  await expect(table.getByText(unrelatedMatter.title, { exact: true })).toHaveCount(0)
  await expect(page.locator('.load-error')).toHaveCount(0)
  await expect(page.getByText('普通筛选权限已过期', { exact: true })).toHaveCount(0)
  await expect.poll(() => page.evaluate(() => (
    window as Window & { __staleDeniedToastCount: number }
  ).__staleDeniedToastCount)).toBe(0)
})

test('被新个人筛选取代的旧里程碑403无副作用', async ({ page }) => {
  const ownedMatters = [
    featureMatter({ id: 328, title: '里程碑竞态本人事项一', ownerId: 7, projectId: 31 }),
    featureMatter({ id: 329, title: '里程碑竞态本人事项二', ownerId: 7, projectId: 32 })
  ]
  let releaseMilestoneResponse: () => void = () => undefined
  const milestoneResponseReleased = new Promise<void>(resolve => {
    releaseMilestoneResponse = resolve
  })
  await page.addInitScript(() => {
    let staleMilestoneToastCount = 0
    const seen = new WeakSet<Element>()
    const countStaleMilestoneToasts = () => {
      document.querySelectorAll('.el-message__content').forEach(element => {
        if (element.textContent?.trim() === '里程碑权限已过期' && !seen.has(element)) {
          seen.add(element)
          staleMilestoneToastCount += 1
        }
      })
    }
    new MutationObserver(countStaleMilestoneToasts).observe(document, { childList: true, subtree: true })
    Object.defineProperty(window, '__staleMilestoneToastCount', {
      configurable: true,
      get: () => staleMilestoneToastCount
    })
  })
  const requestCounts = await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: true, canFeedbackOwn: true },
    matters: ownedMatters,
    onMatterListGet: requestCount => requestCount === 4
      ? {
          status: 403,
          body: { code: 403, message: '里程碑权限已过期' },
          waitUntil: milestoneResponseReleased
        }
      : undefined
  })

  await page.goto('/key-matters')
  const rail = page.getByRole('complementary', { name: '列表快速筛选' })
  const table = page.getByLabel('大事儿列表')
  const ownedButton = rail.getByRole('button', { name: /我的事项/ })
  await expect(table.getByText(ownedMatters[0].title, { exact: true })).toBeVisible()
  await expect.poll(() => requestCounts.matterListGet).toBe(2)

  const milestoneResponse = page.waitForResponse(response => (
    response.request().method() === 'GET'
      && new URL(response.url()).pathname === '/api/key-matters'
      && response.status() === 403
  ))
  await page.getByRole('button', { name: '刷新' }).click()
  await expect.poll(() => requestCounts.matterListGet).toBe(4)

  await ownedButton.click()
  await expect.poll(() => requestCounts.matterListGet).toBe(5)
  await expect(ownedButton).toHaveClass(/\bactive\b/)
  await expect(ownedButton).toHaveAttribute('aria-pressed', 'true')
  await expect(table.getByText(ownedMatters[0].title, { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: '大事儿管理' })).toBeVisible()

  releaseMilestoneResponse()
  await (await milestoneResponse).finished()
  await page.waitForTimeout(100)

  await expect(page).toHaveURL('/key-matters')
  await expect(page.getByRole('link', { name: '大事儿管理' })).toBeVisible()
  await expect(ownedButton).toHaveClass(/\bactive\b/)
  await expect(table.getByText(ownedMatters[0].title, { exact: true })).toBeVisible()
  await expect(page.locator('.load-error')).toHaveCount(0)
  await expect(page.getByText('里程碑权限已过期', { exact: true })).toHaveCount(0)
  await expect.poll(() => requestCounts.access).toBe(1)
  await expect.poll(() => page.evaluate(() => (
    window as Window & { __staleMilestoneToastCount: number }
  ).__staleMilestoneToastCount)).toBe(0)
})

test('旧个人筛选响应不会覆盖新的普通查询', async ({ page }) => {
  const ownedMatter = featureMatter({ id: 330, title: '竞态旧个人事项', ownerId: 7, projectId: 31 })
  const matchingMatter = featureMatter({
    id: 331,
    title: '竞态新普通查询事项',
    ownerId: 16,
    projectId: 32
  })
  const unrelatedMatter = featureMatter({ id: 332, title: '竞态无关事项', ownerId: 16, projectId: 31 })
  const matters = [ownedMatter, matchingMatter, unrelatedMatter]
  let releasePersonalResponse: () => void = () => undefined
  const personalResponseReleased = new Promise<void>(resolve => {
    releasePersonalResponse = resolve
  })
  const requestCounts = await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true },
    matters,
    onMatterListGet: requestCount => requestCount === 3
      ? {
          status: 200,
          body: { code: 200, data: matters },
          waitUntil: personalResponseReleased
        }
      : undefined
  })

  await page.goto('/key-matters')
  const rail = page.getByRole('complementary', { name: '列表快速筛选' })
  const filterBar = page.getByLabel('事项筛选')
  const table = page.getByLabel('大事儿列表')
  const pagination = page.getByLabel('事项列表分页')
  const ownedButton = rail.getByRole('button', { name: /我的事项/ })

  await expect(table.getByText(ownedMatter.title, { exact: true })).toBeVisible()
  await ownedButton.click()
  await expect.poll(() => requestCounts.matterListGet).toBe(3)

  await filterBar.getByPlaceholder('搜索标题或说明').fill('竞态新普通查询事项')
  const ordinaryResponsesCompleted = waitForCompletedMatterListResponses(page, 2)
  await filterBar.getByRole('button', { name: '查询' }).click()
  await ordinaryResponsesCompleted

  await expect(ownedButton).not.toHaveClass(/\bactive\b/)
  await expect(pagination).toContainText('共 1 项')
  await expect(table.getByText(matchingMatter.title, { exact: true })).toBeVisible()
  await expect(table.getByText(ownedMatter.title, { exact: true })).toHaveCount(0)
  await expect(table.getByText(unrelatedMatter.title, { exact: true })).toHaveCount(0)

  const personalRequest = new URL(requestCounts.matterListUrls[2])
  for (const parameter of ['keyword', 'status', 'priority', 'ownerId', 'projectId']) {
    expect(personalRequest.searchParams.has(parameter)).toBe(false)
  }
  const ordinaryRequests = requestCounts.matterListUrls.slice(3, 5).map(url => new URL(url))
  expect(ordinaryRequests.find(url => url.searchParams.has('keyword'))?.searchParams.get('keyword'))
    .toBe('竞态新普通查询事项')
  const completeRequest = ordinaryRequests.find(url => !url.searchParams.has('keyword'))
  expect(completeRequest).toBeDefined()
  for (const parameter of ['keyword', 'status', 'priority', 'ownerId', 'projectId']) {
    expect(completeRequest?.searchParams.has(parameter)).toBe(false)
  }

  const staleResponseCompleted = waitForCompletedMatterListResponses(page, 1)
  releasePersonalResponse()
  await staleResponseCompleted

  await expect(ownedButton).not.toHaveClass(/\bactive\b/)
  await expect(pagination).toContainText('共 1 项')
  await expect(table.getByText(matchingMatter.title, { exact: true })).toBeVisible()
  await expect(table.getByText(ownedMatter.title, { exact: true })).toHaveCount(0)
  await expect(table.getByText(unrelatedMatter.title, { exact: true })).toHaveCount(0)
})

test('切换个人快捷筛选回到默认每页十条的第一页', async ({ page }) => {
  const ownedMatters = Array.from({ length: 11 }, (_, index) => featureMatter({
    id: 400 + index,
    title: `切换分页本人负责事项 ${index + 1}`,
    ownerId: 7,
    projectId: 31,
    participantIds: [7, 8]
  }))
  const participatingMatters = Array.from({ length: 11 }, (_, index) => featureMatter({
    id: 450 + index,
    title: `切换分页本人参与事项 ${index + 1}`,
    ownerId: 16,
    projectId: 32,
    participantIds: [16, 7]
  }))
  await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true },
    matters: [...ownedMatters, ...participatingMatters]
  })

  await page.goto('/key-matters')
  const rail = page.getByRole('complementary', { name: '列表快速筛选' })
  const ownedButton = rail.getByRole('button', { name: /我的事项/ })
  const participatingButton = rail.getByRole('button', { name: /我参与的事项/ })
  const table = page.getByLabel('大事儿列表')
  const rows = table.locator('.el-table__body-wrapper tbody tr')
  const pagination = page.getByLabel('事项列表分页')

  await expect(ownedButton).toContainText('11 项由我负责')
  await expect(participatingButton).toContainText('11 项协作参与')
  await ownedButton.click()
  await expect(pagination).toContainText('共 11 项')
  await expect(rows).toHaveCount(10)

  await pagination.locator('.btn-next').click()
  await expect(pagination.locator('.number.is-active')).toHaveText('2')
  await expect(rows).toHaveCount(1)
  await expect(table.getByText(ownedMatters[10].title, { exact: true })).toBeVisible()

  await participatingButton.click()
  await expect(participatingButton).toHaveClass(/\bactive\b/)
  await expect(ownedButton).not.toHaveClass(/\bactive\b/)
  await expect(pagination).toContainText('共 11 项')
  await expect(pagination.locator('.number.is-active')).toHaveText('1')
  await expect(rows).toHaveCount(10)
  await expect(table.getByText(participatingMatters[0].title, { exact: true })).toBeVisible()
  await expect(table.getByText(participatingMatters[9].title, { exact: true })).toBeVisible()
  await expect(table.getByText(participatingMatters[10].title, { exact: true })).toHaveCount(0)
  await expect(table.getByText(ownedMatters[10].title, { exact: true })).toHaveCount(0)
})

test('刷新保留个人快捷筛选、忽略未提交普通筛选并在结果缩短后校正分页', async ({ page }) => {
  const ownedMatters = Array.from({ length: 21 }, (_, index) => featureMatter({
    id: 500 + index,
    title: `刷新本人负责事项 ${index + 1}`,
    ownerId: 7,
    projectId: index % 2 === 0 ? 31 : 32,
    participantIds: [7, 8]
  }))
  const unrelatedMatter = featureMatter({
    id: 600,
    title: '刷新无关事项',
    ownerId: 16,
    projectId: 31,
    participantIds: [16, 8]
  })
  const fixtureMatters = [...ownedMatters, unrelatedMatter]
  const requestCounts = await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true },
    matters: fixtureMatters
  })

  await page.goto('/key-matters')
  const rail = page.getByRole('complementary', { name: '列表快速筛选' })
  const filterBar = page.getByLabel('事项筛选')
  const ownedButton = rail.getByRole('button', { name: /我的事项/ })
  const table = page.getByLabel('大事儿列表')
  const rows = table.locator('.el-table__body-wrapper tbody tr')
  const pagination = page.getByLabel('事项列表分页')
  const keywordControl = filterBar.getByPlaceholder('搜索标题或说明')
  const prioritySelect = filterBar.getByRole('combobox', { name: '优先级' })
  const statusSelect = filterBar.getByRole('combobox', { name: '状态' })
  const ownerControl = filterBar.getByRole('combobox', { name: '负责人' })
  const projectControl = filterBar.getByRole('combobox', { name: '关联项目' })

  await expect(prioritySelect).toBeVisible()
  await expect(statusSelect).toBeVisible()
  await expect(ownerControl).toBeVisible()
  await expect(projectControl).toBeVisible()
  await expect(ownedButton).toContainText('21 项由我负责')
  await ownedButton.click()
  await pagination.locator('.btn-next').click()
  await pagination.locator('.btn-next').click()
  await expect(ownedButton).toHaveClass(/\bactive\b/)
  await expect(pagination).toContainText('共 21 项')
  await expect(pagination.locator('.number.is-active')).toHaveText('3')
  await expect(rows).toHaveCount(1)
  await expect(table.getByText(ownedMatters[20].title, { exact: true })).toBeVisible()
  await expect(table.getByText(ownedMatters[0].title, { exact: true })).toHaveCount(0)

  await keywordControl.fill('未提交的关键词')
  await statusSelect.press('ArrowDown')
  await page.getByRole('option', { name: '有风险', exact: true }).click()
  await expect(filterBar).toContainText('有风险')
  await prioritySelect.press('ArrowDown')
  await page.getByRole('option', { name: 'P1', exact: true }).click()
  await expect(filterBar).toContainText('P1')
  await ownerControl.fill('负责人乙')
  await ownerControl.press('ArrowDown')
  await ownerControl.press('Enter')
  await projectControl.fill('数据治理平台')
  await projectControl.press('ArrowDown')
  await projectControl.press('Enter')

  const refreshBaselineUrlIndex = requestCounts.matterListUrls.length
  const unchangedRefreshCompleted = waitForCompletedMatterListResponses(page, 2)
  await page.getByRole('button', { name: '刷新' }).click()
  await unchangedRefreshCompleted

  const unchangedRefreshUrls = requestCounts.matterListUrls
    .slice(refreshBaselineUrlIndex)
    .map(url => new URL(url))
  expect(unchangedRefreshUrls.length).toBeGreaterThan(0)
  for (const url of unchangedRefreshUrls) {
    for (const parameter of ['keyword', 'status', 'priority', 'ownerId', 'projectId']) {
      expect(url.searchParams.has(parameter)).toBe(false)
    }
  }

  await expect(keywordControl).toHaveValue('未提交的关键词')
  await expect(filterBar).toContainText('有风险')
  await expect(filterBar).toContainText('P1')
  await expect(filterBar).toContainText('负责人乙')
  await expect(filterBar).toContainText('数据治理平台')
  await expect(ownedButton).toHaveClass(/\bactive\b/)
  await expect(ownedButton).toContainText('21 项由我负责')
  await expect(pagination).toContainText('共 21 项')
  await expect(pagination.locator('.number.is-active')).toHaveText('3')
  await expect(rows).toHaveCount(1)
  await expect(table.getByText(ownedMatters[20].title, { exact: true })).toBeVisible()

  fixtureMatters.splice(0, fixtureMatters.length, ...ownedMatters.slice(0, 15), unrelatedMatter)
  const shortenedRefreshCompleted = waitForCompletedMatterListResponses(page, 2)
  await page.getByRole('button', { name: '刷新' }).click()
  await shortenedRefreshCompleted

  await expect(ownedButton).toHaveClass(/\bactive\b/)
  await expect(ownedButton).toContainText('15 项由我负责')
  await expect(pagination).toContainText('共 15 项')
  await expect(pagination.locator('.number.is-active')).toHaveText('2')
  await expect(pagination.locator('.btn-next')).toBeDisabled()
  await expect(rows).toHaveCount(5)
  for (const matter of ownedMatters.slice(10, 15)) {
    await expect(table.getByText(matter.title, { exact: true })).toBeVisible()
  }
  await expect(table.getByText(ownedMatters[0].title, { exact: true })).toHaveCount(0)
  await expect(table.getByText(ownedMatters[20].title, { exact: true })).toHaveCount(0)
  await expect(table.getByText(unrelatedMatter.title, { exact: true })).toHaveCount(0)
})

test('个人快捷筛选显示上下文空态且无作用域和个人作用域均不溢出', async ({ page, context }) => {
  await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: false },
    matters: []
  })

  await page.setViewportSize({ width: 320, height: 844 })
  await page.goto('/key-matters')
  const rail = page.getByRole('complementary', { name: '列表快速筛选' })
  const pagination = page.getByLabel('事项列表分页')
  const ownedButton = rail.getByRole('button', { name: /我的事项/ })
  const participatingButton = rail.getByRole('button', { name: /我参与的事项/ })

  await expect(pagination).toContainText('共 0 项')
  await expect(page.getByText('暂无大事儿', { exact: true })).toBeVisible()
  const allButton = rail.getByRole('button', { name: '全部事项' })
  await expect(page.getByText(/点击右上角新增事项/)).toHaveCount(0)
  await expect(page.locator('.load-error')).toHaveCount(0)
  await expect(ownedButton).toBeVisible()
  await expect(participatingButton).toBeVisible()

  for (const width of [320, 390]) {
    await page.setViewportSize({ width, height: 844 })

    await allButton.click()
    await expect(allButton).toHaveClass(/\bactive\b/)
    expect(await page.evaluate(() =>
      document.documentElement.scrollWidth > document.documentElement.clientWidth
    )).toBe(false)

    await ownedButton.click()
    await expect(page.getByText('暂无我负责的事项', { exact: true })).toBeVisible()
    await expect(page.getByText(/点击右上角新增事项/)).toHaveCount(0)
    expect(await page.evaluate(() =>
      document.documentElement.scrollWidth > document.documentElement.clientWidth
    )).toBe(false)

    await participatingButton.click()
    await expect(page.getByText('暂无我参与的事项', { exact: true })).toBeVisible()
    await expect(page.getByText(/点击右上角新增事项/)).toHaveCount(0)
    expect(await page.evaluate(() =>
      document.documentElement.scrollWidth > document.documentElement.clientWidth
    )).toBe(false)
  }

  const adminPage = await context.newPage()
  await mockFeatureSession(adminPage, {
    user: { id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' },
    access: { canAccess: true, canManageAll: true, canFeedbackOwn: true, canCreateOwn: true },
    matters: []
  })
  await adminPage.goto('/key-matters')
  await expect(adminPage.getByText('暂无大事儿，点击右上角新增事项', { exact: true })).toBeVisible()
  await adminPage.close()
})

test('管理员可以维护参与人且负责人自动保留', async ({ page }) => {
  const admin = { id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' }
  const matter = featureMatter({ id: 41, title: '管理员维护参与人事项', ownerId: 7, projectId: 31 })
  matter.participants = [matter.participants[0]]
  await mockFeatureSession(page, {
    user: admin,
    access: { canAccess: true, canManageAll: true, canFeedbackOwn: true, canCreateOwn: true },
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
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true, canCreateOwn: true },
    matters: [ownMatter, otherMatter]
  })

  await page.goto('/key-matters')
  const table = page.getByLabel('大事儿列表')
  const ownRow = table.locator('tr', { hasText: ownMatter.title })
  const otherRow = table.locator('tr', { hasText: otherMatter.title })
  await expect(ownRow.getByRole('button', { name: '更新周进展' })).toBeVisible()
  await expect(otherRow.getByRole('button', { name: '更新周进展' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新增事项' })).toBeVisible()
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

  const activeGroupMatter = page.locator('.presentation-group-matters button', { hasText: firstMatter.title })
  await expect(activeGroupMatter.locator('i')).toHaveClass(/waiting/)
  await expect(activeGroupMatter.locator('i')).not.toHaveClass(/pending/)
  const activeThumbnail = stage.getByRole('button', { name: '跳转到第 1 项' })
  await expect(activeThumbnail).toHaveClass(/waiting/)
  await expect(activeThumbnail).not.toHaveClass(/pending/)
  await expect(activeThumbnail.locator('.el-icon')).toHaveCount(0)
  await expect(activeThumbnail.locator('span')).toHaveText('1')
})

test('演示参与人信息在1024宽度不裁剪进度控件', async ({ page }) => {
  await page.setViewportSize({ width: 1024, height: 768 })
  const matter = featureMatter({ id: 41, title: '多参与人演示布局事项', ownerId: 7, projectId: 31 })
  matter.participants.push(
    { userId: 17, username: 'participant-seventeen', realName: '参与成员丙' },
    { userId: 18, username: 'participant-eighteen', realName: '参与成员丁' }
  )
  await mockFeatureSession(page, {
    user: { id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' },
    access: { canAccess: true, canManageAll: true, canFeedbackOwn: true },
    matters: [matter]
  })

  await page.goto('/key-matters-meeting')
  const stage = page.getByLabel('周会演示模式')
  await expect(stage.getByLabel('演示中更新周报')).toBeVisible()

  const geometry = await stage.evaluate(element => {
    const bounds = (selector: string) => {
      const target = element.querySelector<HTMLElement>(selector)
      if (!target) throw new Error(`Missing presentation element: ${selector}`)
      const box = target.getBoundingClientRect()
      return { left: box.left, right: box.right, top: box.top, bottom: box.bottom }
    }
    const meta = element.querySelector<HTMLElement>('.presentation-meta')
    if (!meta) throw new Error('Missing presentation metadata')
    return {
      metaFits: meta.scrollWidth <= meta.clientWidth + 1,
      card: bounds('.presentation-card'),
      progress: bounds('.presentation-inline-progress'),
      progressEditor: bounds('.presentation-progress-editor'),
      progressBar: bounds('.progress-readonly-bar'),
      presets: bounds('.progress-presets'),
      progressInput: bounds('.presentation-progress-editor .el-input-number')
    }
  })

  expect(geometry.metaFits).toBe(true)
  const editor = stage.locator('.presentation-progress-editor')
  await editor.getByRole('button', { name: '100%' }).click()
  await expect(editor.locator('.el-input-number input')).toHaveValue('100')
  await expect(stage.locator('.presentation-meta-status .status-已完成')).toHaveClass(/active/)
  await editor.getByRole('button', { name: '75%' }).click()
  await expect(editor.locator('.el-input-number input')).toHaveValue('75')
  await expect(stage.locator('.presentation-meta-status .status-推进中')).toHaveClass(/active/)
  for (const width of [390]) {
    await page.setViewportSize({ width, height: 844 })
    const mobile = await stage.evaluate(element => {
      const card = element.querySelector<HTMLElement>('.presentation-card')!
      const controls = Array.from(element.querySelectorAll<HTMLElement>('.presentation-progress-editor, .progress-presets, .presentation-progress-editor .el-input-number'))
      return {
        overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
        inside: controls.every(control => {
          const box = control.getBoundingClientRect()
          const cardBox = card.getBoundingClientRect()
          return box.left >= cardBox.left - 1 && box.right <= cardBox.right + 1
        })
      }
    })
    expect(mobile.overflow).toBe(false)
    expect(mobile.inside).toBe(true)
  }
})

test('负责人变更后保存周进度刷新为只读', async ({ page }) => {
  const matter = featureMatter({ id: 41, title: '负责人变更事项', ownerId: 7, projectId: 31 })
  let ownerChanged = false
  const requestCounts = await mockFeatureSession(page, {
    user: featureUsers[0],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: true, canCreateOwn: true },
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

test('离开页面后迟到403不会改写当前路由', async ({ page }) => {
  const matter = featureMatter({ id: 41, title: '延迟权限响应事项', ownerId: 7, projectId: 31 })
  let accessRevoked = false
  let releaseReads = () => {}
  const readsRelease = new Promise<void>(resolve => {
    releaseReads = resolve
  })
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
    new MutationObserver(countDeniedToasts).observe(document, { childList: true, subtree: true })
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
    onMatterListGet: () => ({
      status: 403,
      body: { code: 403, message: '无权访问大事儿' },
      waitUntil: readsRelease
    })
  })

  await page.goto('/key-matters')
  await expect.poll(() => requestCounts.matterListGet).toBe(2)
  await page.getByRole('link', { name: '项目管理' }).click()
  await expect(page).toHaveURL('/projects')

  accessRevoked = true
  releaseReads()
  await page.waitForTimeout(500)

  await expect(page).toHaveURL('/projects')
  expect(requestCounts.access).toBe(1)
  await expect.poll(() => page.evaluate(() => (window as Window & { __deniedToastCount: number }).__deniedToastCount)).toBe(0)
})

test('重新进入大事儿后403仍会刷新权限并退出', async ({ page }) => {
  const matter = featureMatter({ id: 41, title: '重新进入权限撤销事项', ownerId: 7, projectId: 31 })
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
    new MutationObserver(countDeniedToasts).observe(document, { childList: true, subtree: true })
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
      if (requestCount <= 2) {
        return {
          status: 200,
          body: { code: 200, message: 'success', data: [matter] }
        }
      }
      accessRevoked = true
      return {
        status: 403,
        body: { code: 403, message: '无权访问大事儿' }
      }
    }
  })

  await page.goto('/key-matters')
  await expect(page).toHaveURL('/key-matters')
  await expect(page.getByLabel('大事儿列表').getByText(matter.title)).toBeVisible()
  await expect.poll(() => requestCounts.matterListGet).toBe(2)
  expect(requestCounts.access).toBe(1)

  await page.getByRole('link', { name: '项目管理' }).click()
  await expect(page).toHaveURL('/projects')
  await expect(page.locator('.page-title')).toHaveText('项目管理')

  await page.getByRole('link', { name: '大事儿管理' }).click()

  await expect(page).toHaveURL('/')
  await expect.poll(() => requestCounts.matterListGet).toBe(4)
  await expect.poll(() => requestCounts.access).toBe(3)
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

test('普通创建者只能创建本人事项并在创建后获得周进展入口', async ({ page }) => {
  const user = featureUsers[0]
  let created: FeatureMatterFixture | undefined
  let accessRefresh = false
  const matter = featureMatter({ id: 51, title: '已有事项', ownerId: 16, projectId: 31 })
  const matters: FeatureMatterFixture[] = [matter]
  const requestCounts = await mockFeatureSession(page, {
    user,
    access: () => ({ canAccess: true, canManageAll: false, canFeedbackOwn: accessRefresh, canCreateOwn: true }),
    matters,
    onMatterPost: payload => {
      const data = payload as Record<string, unknown>
      expect(data.ownerId).toBe(user.id)
      expect(data.participantIds).toEqual(expect.arrayContaining([user.id]))
      created = { ...matter, id: 99, title: String(data.title), ownerId: user.id, ownerName: user.realName, currentWeekUpdated: false, currentWeekUpdate: null, latestUpdate: null, weeklyUpdates: [] }
      matters.push(created)
      accessRefresh = true
      return { body: { code: 200, data: created } }
    }
  })
  await page.goto('/key-matters')
  await expect(page.getByRole('button', { name: '新增事项' })).toBeVisible()
  await page.getByRole('button', { name: '新增事项' }).click()
  const drawer = page.getByRole('dialog', { name: '新增大事儿' })
  await expect(drawer.getByLabel('负责人')).toBeDisabled()
  await expect(drawer.getByLabel('参与人')).toHaveCount(0)
  await drawer.getByRole('button', { name: '100%' }).click()
  await expect(drawer.locator('.el-form-item', { hasText: '状态' })).toContainText('已完成')
  await drawer.getByRole('button', { name: '75%' }).click()
  await expect(drawer.locator('.el-form-item', { hasText: '状态' })).toContainText('推进中')
  await expect(drawer.getByLabel('当前进度数值')).toHaveAttribute('step', '5')
  await drawer.getByLabel('事项标题').fill('自己创建事项')
  await drawer.getByLabel('负责人').evaluate((el, id) => { (el as HTMLInputElement).value = String(id) }, 16)
  await drawer.getByLabel('开始日期').fill('2026-03-20')
  await drawer.getByLabel('计划完成').fill('2026-04-30')
  await drawer.getByRole('button', { name: '保存' }).click()
  await expect.poll(() => requestCounts.access).toBeGreaterThan(1)
  await expect.poll(() => accessRefresh).toBe(true)
  const createdRow = page.getByLabel('大事儿列表').locator('tr', { hasText: '自己创建事项' })
  await expect(createdRow).toBeVisible()
  await expect(createdRow).toContainText(user.realName)
  await expect(createdRow.getByRole('button', { name: '更新周进展' })).toBeVisible()
  await expect(page.getByRole('button', { name: '编辑事项' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '删除事项' })).toHaveCount(0)
})

test('无canCreateOwn用户不显示新增入口', async ({ page }) => {
  await mockFeatureSession(page, {
    user: featureUsers[2],
    access: { canAccess: true, canManageAll: false, canFeedbackOwn: false, canCreateOwn: false },
    matters: []
  })
  await page.goto('/key-matters')
  await expect(page.getByRole('button', { name: '新增事项' })).toHaveCount(0)
  await expect(page.getByText(/点击右上角新增事项/)).toHaveCount(0)
})

test('周进展进度快捷按钮和数值输入同步状态并提交', async ({ page }) => {
  const matter = featureMatter({ id: 61, title: '进度联动事项', ownerId: 7, projectId: 31 })
  let payload: Record<string, unknown> | undefined
  await mockFeatureSession(page, {
    user: featureUsers[0], access: { canAccess: true, canManageAll: false, canFeedbackOwn: true, canCreateOwn: true }, matters: [matter],
    onWeeklyPut: () => ({ status: 200, body: { code: 200, data: payload } })
  })
  await page.goto('/key-matters')
  await page.getByRole('button', { name: '更新周进展' }).click()
  const drawer = page.getByRole('dialog', { name: '更新周进展' })
  await drawer.getByRole('button', { name: '100%' }).click()
  await expect(drawer.locator('.el-form-item', { hasText: '事项状态' })).toContainText('已完成')
  await drawer.getByRole('button', { name: '50%' }).click()
  await expect(drawer.getByLabel('完成进度数值')).toHaveValue('50')
  await expect(drawer.locator('.el-form-item', { hasText: '事项状态' })).toContainText('推进中')
  await drawer.getByRole('textbox', { name: '本周成果' }).fill('完成进度联动')
  const request = page.waitForRequest(req => req.method() === 'PUT' && req.url().includes('/weekly-updates/'))
  await drawer.getByRole('button', { name: '保存周进展' }).click()
  payload = (await request).postDataJSON()
  expect(payload.progress).toBe(50)
  expect(payload.status).toBe('推进中')
})
