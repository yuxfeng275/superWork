import { expect, test, type Page, type Route } from '@playwright/test'

const adminKeyMatterAccess = {
  canAccess: true,
  canManageAll: true,
  canFeedbackOwn: true
}

function fulfillAdminKeyMatterAccess(route: Route) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: adminKeyMatterAccess })
  })
}

const coreMatters = [
  {
    id: 11,
    title: '皇家会员体系二期上线',
    description: '完成核心会员链路切换',
    projectId: 3,
    projectName: '皇家全渠道定制项目',
    ownerId: 7,
    ownerName: '石家乐',
    participants: [
      { userId: 7, username: 'shijiale', realName: '石家乐' },
      { userId: 16, username: 'yufeng', realName: '于峰' }
    ],
    priority: 'P0',
    status: '有风险',
    progress: 60,
    startDate: '2026-08-03',
    plannedCompletionDate: '2026-08-28',
    sortOrder: 0,
    overdue: false,
    currentWeekUpdated: true,
    latestUpdate: {
      id: 21,
      weekStartDate: '2026-08-03',
      status: '有风险',
      progress: 60,
      progressSummary: '核心链路联调完成',
      issues: '上线窗口仍待确认',
      nextWeekPlan: '完成灰度验证',
      supportNeeded: '确认上线窗口'
    },
    currentWeekUpdate: {
      id: 21,
      weekStartDate: '2026-08-03',
      status: '有风险',
      progress: 60,
      progressSummary: '核心链路联调完成',
      issues: '上线窗口仍待确认',
      nextWeekPlan: '完成灰度验证',
      supportNeeded: '确认上线窗口'
    },
    weeklyUpdates: [
      {
        id: 21,
        weekStartDate: '2026-08-03',
        status: '有风险',
        progress: 60,
        progressSummary: '核心链路联调完成',
        issues: '上线窗口仍待确认',
        nextWeekPlan: '完成灰度验证',
        supportNeeded: '确认上线窗口'
      },
      {
        id: 20,
        weekStartDate: '2026-07-27',
        status: '推进中',
        progress: 40,
        progressSummary: '完成方案评审'
      }
    ]
  },
  {
    id: 12,
    title: '运营平台数据质量治理',
    projectId: null,
    ownerId: 16,
    ownerName: '于峰',
    participants: [
      { userId: 16, username: 'yufeng', realName: '于峰' },
      { userId: 7, username: 'shijiale', realName: '石家乐' }
    ],
    priority: 'P1',
    status: '推进中',
    progress: 35,
    startDate: '2026-08-03',
    plannedCompletionDate: '2026-09-15',
    sortOrder: 1,
    overdue: false,
    currentWeekUpdated: false,
    latestUpdate: null,
    currentWeekUpdate: null,
    weeklyUpdates: []
  }
]

const matters = [
  ...coreMatters,
  ...Array.from({ length: 11 }, (_, index) => ({
    id: 101 + index,
    title: `分页验证事项 ${index + 1}`,
    description: `用于验证台账分页的事项 ${index + 1}`,
    projectId: index % 2 === 0 ? 3 : null,
    projectName: index % 2 === 0 ? '皇家全渠道定制项目' : undefined,
    ownerId: index % 2 === 0 ? 7 : 16,
    ownerName: index % 2 === 0 ? '石家乐' : '于峰',
    participants: index % 2 === 0
      ? [{ userId: 7, username: 'shijiale', realName: '石家乐' }]
      : [{ userId: 16, username: 'yufeng', realName: '于峰' }],
    priority: index % 3 === 0 ? 'P1' : 'P2',
    status: '推进中',
    progress: 10 + index,
    startDate: '2026-08-03',
    plannedCompletionDate: `2026-09-${String(index + 10).padStart(2, '0')}`,
    sortOrder: index + 2,
    overdue: false,
    currentWeekUpdated: false,
    latestUpdate: null,
    currentWeekUpdate: null,
    weeklyUpdates: []
  }))
]

// 已完成的重点事项：无需再提交周进展。该夹具只用于专属用例，不混入默认台账/分页夹具。
const completedMatterWithoutWeeklyUpdate = {
  id: 13,
  title: '完成事项无需周报',
  description: '已闭环的重点事项，无需继续提交周进展',
  projectId: null,
  ownerId: 16,
  ownerName: '于峰',
  priority: 'P2',
  status: '已完成',
  progress: 100,
  startDate: '2026-08-03',
  plannedCompletionDate: '2026-08-21',
  completedAt: '2026-08-21',
  sortOrder: 2,
  overdue: false,
  currentWeekUpdated: false,
  latestUpdate: null,
  currentWeekUpdate: null,
  weeklyUpdates: []
}

// 已完成的重点事项且携带当前周周报：用于校验历史修正入口保留、周会只读。该夹具只用于专属用例。
const completedWeekReport = {
  id: 31,
  weekStartDate: '2026-08-03',
  status: '已完成',
  progress: 100,
  progressSummary: '完成交付并通过验收',
  issues: '',
  nextWeekPlan: '',
  supportNeeded: ''
}

const completedMatterWithWeeklyUpdate = {
  id: 14,
  title: '完成事项保留历史修正入口',
  description: '已完成并携带当前周周报，验证历史周报仍可修正且周会只读',
  projectId: null,
  ownerId: 16,
  ownerName: '于峰',
  priority: 'P1',
  status: '已完成',
  progress: 100,
  startDate: '2026-07-27',
  plannedCompletionDate: '2026-08-09',
  completedAt: '2026-08-07',
  sortOrder: 0,
  overdue: false,
  currentWeekUpdated: true,
  latestUpdate: completedWeekReport,
  currentWeekUpdate: completedWeekReport,
  weeklyUpdates: [
    completedWeekReport,
    {
      id: 30,
      weekStartDate: '2026-07-27',
      status: '推进中',
      progress: 80,
      progressSummary: '进入交付验收阶段',
      issues: '',
      nextWeekPlan: '',
      supportNeeded: ''
    }
  ]
}

// 列表与会演示使用同一数组：保留一条已更新事项，用于校验完成事项不会拉低更新占比。
const completedMatterList = [coreMatters[0], completedMatterWithoutWeeklyUpdate]

async function installCompletedMatterRoutes(page: Page) {
  await page.unroute('**/api/key-matters**')
  await page.route('**/api/key-matters**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/key-matters/access') return fulfillAdminKeyMatterAccess(route)
    if (request.method() !== 'GET') {
      return route.fulfill({
        status: 405,
        contentType: 'application/json',
        body: JSON.stringify({ code: 405, message: '只读用例不支持写入 key-matters' })
      })
    }
    if (path === '/api/key-matters/13') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: completedMatterWithoutWeeklyUpdate })
      })
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: completedMatterList })
    })
  })
}

test.beforeEach(async ({ page }) => {
  // 冻结浏览器时钟：夹具周为 2026-08-03 起的一周，当前真实日期已进入 8 月下旬，
  // 直接使用 `new Date()` 会让「本周」偏移到 2026-08-17 周，导致台账/周会断言失败。
  await page.clock.setFixedTime(new Date('2026-08-05T09:00:00+08:00'))
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify({
      id: 1,
      username: 'admin',
      realName: '系统管理员',
      role: 'DIRECTOR'
    }))
  })
  await page.route('**/api/requirements**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: { records: [], total: 0 } })
  }))
  await page.route('**/api/projects**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: { records: [{ id: 3, name: '皇家全渠道定制项目' }] } })
  }))
  await page.route('**/api/users**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data: { records: [
      { id: 7, username: 'shijiale', realName: '石家乐', status: 1 },
      { id: 16, username: 'yufeng', realName: '于峰', status: 1 }
    ] } })
  }))
  await page.route('**/api/key-matters**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/key-matters/access') return fulfillAdminKeyMatterAccess(route)
    if (request.method() === 'POST' || request.method() === 'PUT') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: request.postDataJSON() })
      })
    }
    if (path === '/api/key-matters/11') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: coreMatters[0] })
      })
    }
    if (path === '/api/key-matters/meeting') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: coreMatters })
      })
    }
    const params = new URL(request.url()).searchParams
    const ownerId = params.get('ownerId')
    const projectId = params.get('projectId')
    const keyword = params.get('keyword')
    const status = params.get('status')
    const priority = params.get('priority')
    const records = matters.filter(item =>
      (!ownerId || String(item.ownerId) === ownerId)
      && (!projectId || String(item.projectId) === projectId)
      && (!keyword || item.title.includes(keyword) || item.description?.includes(keyword))
      && (!status || item.status === status)
      && (!priority || item.priority === priority)
    )
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: records })
    })
  })
})

test('事项概览与月份标题同排且不受快速筛选影响', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/key-matters')

  const toolbar = page.getByLabel('大事儿操作栏')
  const overview = toolbar.getByLabel('事项概览')
  const titlebar = toolbar.locator('.register-titlebar')
  await expect(titlebar.getByRole('heading', { name: '8月大事儿' })).toBeVisible()
  await expect(overview).toBeVisible()
  await expect(overview.locator('.summary-cell.all')).toContainText('13')
  await expect(overview.locator('.summary-cell.risk')).toContainText('1')

  const [titleBox, overviewBox] = await Promise.all([titlebar.boundingBox(), overview.boundingBox()])
  expect(titleBox).not.toBeNull()
  expect(overviewBox).not.toBeNull()
  expect(titleBox!.y).toBeLessThan(overviewBox!.y + overviewBox!.height)
  expect(overviewBox!.y).toBeLessThan(titleBox!.y + titleBox!.height)

  const listRail = page.getByRole('complementary', { name: '列表快速筛选' })
  await listRail.getByRole('button', { name: /于峰/ }).click()
  await expect(page.getByText('运营平台数据质量治理')).toBeVisible()
  await expect(page.getByText('皇家会员体系二期上线')).toHaveCount(0)
  await expect(overview.locator('.summary-cell.all')).toContainText('13')
  await expect(overview.locator('.summary-cell.risk')).toContainText('1')

  if (process.env.CAPTURE_KEY_MATTERS === '1') {
    await page.screenshot({ path: testInfo.outputPath('register-toolbar-summary.png'), fullPage: true })
  }

  await page.setViewportSize({ width: 390, height: 844 })
  const mobileOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > document.documentElement.clientWidth
  )
  expect(mobileOverflow).toBe(false)
})

test('事项列表支持分页、每页条数切换并在筛选后回到第一页', async ({ page }) => {
  await page.goto('/key-matters')

  const table = page.getByLabel('大事儿列表')
  const pagination = page.getByLabel('事项列表分页')
  await expect(pagination).toContainText('共 13 项')
  await expect(table.locator('.el-table__body-wrapper tbody tr')).toHaveCount(10)

  await pagination.locator('.btn-next').click()
  await expect(pagination.locator('.number.is-active')).toHaveText('2')
  await expect(table.locator('.el-table__body-wrapper tbody tr')).toHaveCount(3)
  await expect(table.getByText('分页验证事项 11')).toBeVisible()

  await page.getByRole('complementary', { name: '列表快速筛选' }).getByRole('button', { name: /于峰/ }).click()
  await expect(pagination.locator('.number.is-active')).toHaveText('1')

  await pagination.locator('.el-select').click()
  await page.getByRole('option', { name: '20条/页' }).click()
  await expect(table.locator('.el-table__body-wrapper tbody tr')).toHaveCount(6)
  await expect(pagination.locator('.number.is-active')).toHaveText('1')

  await page.getByRole('complementary', { name: '列表快速筛选' }).getByRole('button', { name: '全部事项' }).click()
  await page.getByPlaceholder('搜索标题或说明').fill('分页验证事项 11')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(pagination.locator('.number.is-active')).toHaveText('1')
  await expect(table.getByText('分页验证事项 11')).toBeVisible()
})

test('台账可查看状态、详情并维护本周进展', async ({ page }, testInfo) => {
  await page.goto('/key-matters')

  await expect(page.getByRole('link', { name: '大事儿管理' })).toBeVisible()
  await expect(page.getByText('皇家会员体系二期上线')).toBeVisible()
  await expect(page.getByLabel('大事儿列表').getByText('本周待更新').first()).toBeVisible()
    const listRail = page.getByRole('complementary', { name: '列表快速筛选' })
    await expect(listRail.getByRole('button', { name: /皇家全渠道定制项目/ })).toBeVisible()
    await expect(page.getByLabel('大事儿列表').locator('.list-status-tag.status-risk')).toBeVisible()
    await expect(page.getByLabel('大事儿列表').locator('.list-status-tag.status-progressing').first()).toBeVisible()
    await listRail.getByRole('button', { name: /于峰/ }).click()
  await expect(page.getByText('运营平台数据质量治理')).toBeVisible()
  await expect(page.getByText('皇家会员体系二期上线')).toHaveCount(0)
  await listRail.getByRole('button', { name: '全部事项' }).click()
  await expect(page.getByText('皇家会员体系二期上线')).toBeVisible()
  await page.getByText('皇家会员体系二期上线').click()

  const detail = page.locator('.detail-content')
  await expect(detail.getByLabel('最新周进展').getByText('核心链路联调完成')).toBeVisible()
  await expect(detail.getByLabel('事项总进度')).toContainText('60')
  await expect(detail.getByLabel('事项关键信息')).toContainText('皇家全渠道定制项目')
  await expect(detail.getByLabel('最新周进展')).toContainText('下一步行动')
  await expect(detail.getByLabel('周进展记录')).toContainText('+20%')

  await page.setViewportSize({ width: 390, height: 844 })
  const detailOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > document.documentElement.clientWidth
  )
  expect(detailOverflow).toBe(false)
  await detail.getByRole('button', { name: '更新周进展' }).click()

  const weekly = page.getByRole('dialog', { name: '更新周进展' })
  await expect(weekly.getByText('结构化周进展')).toBeVisible()
  await expect(weekly.getByLabel('周期状态与进度')).toBeVisible()
  await expect(weekly.getByRole('heading', { name: '本周成果' })).toBeVisible()
  await expect(weekly.getByRole('heading', { name: '问题 / 风险' })).toBeVisible()
  await expect(weekly.getByRole('heading', { name: '需协调 / 决策' })).toBeVisible()
  await expect(weekly.getByRole('heading', { name: '下一步行动' })).toBeVisible()
  await expect(weekly.getByRole('textbox', { name: '本周成果' })).toHaveValue('核心链路联调完成')
  if (process.env.CAPTURE_KEY_MATTERS === '1') {
    await page.screenshot({ path: testInfo.outputPath('weekly-workspace-mobile.png'), fullPage: true })
  }
})

test('周会首次访问等待数据完成后再显示事项内容', async ({ page }) => {
  let releaseMeeting!: () => void
  const meetingGate = new Promise<void>(resolve => { releaseMeeting = resolve })
  await page.unroute('**/api/key-matters**')
  await page.route('**/api/key-matters**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/key-matters/access') return fulfillAdminKeyMatterAccess(route)
    if (path === '/api/key-matters/meeting') await meetingGate
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: coreMatters })
    })
  })

  const meetingRequest = page.waitForRequest(request =>
    new URL(request.url()).pathname === '/api/key-matters/meeting'
  )
  await page.goto('/key-matters-meeting')
  await meetingRequest
  await expect(page.getByLabel('周会模式加载中')).toBeVisible()
  await expect(page.getByLabel('周会演示模式')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '皇家会员体系二期上线' })).toHaveCount(0)

  releaseMeeting()
  await expect(page.getByLabel('周会模式加载中')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '皇家会员体系二期上线' })).toBeVisible()
})

test('周会模式从大事儿页面新标签页打开', async ({ page }) => {
  await page.goto('/key-matters')
  await page.evaluate(() => {
    window.open = ((url?: string | URL, target?: string) => {
      sessionStorage.setItem('meeting-open-url', String(url || ''))
      sessionStorage.setItem('meeting-open-target', target || '')
      return null
    }) as typeof window.open
  })

  await page.getByRole('button', { name: '进入周会全屏' }).click()
  await expect.poll(() => page.evaluate(() => sessionStorage.getItem('meeting-open-url')))
    .toContain('/key-matters-meeting')
  await expect.poll(() => page.evaluate(() => sessionStorage.getItem('meeting-open-target')))
    .toBe('_blank')
  await expect(page).toHaveURL(/\/key-matters$/)
})

test('周会演示按负责人和项目分组并展示结构化简报', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/key-matters-meeting')
  const stage = page.getByLabel('周会演示模式')
  const rail = page.getByRole('complementary', { name: '演示分组导航' })
  const history = page.getByRole('complementary', { name: '大事儿历史周报' })
  await expect(stage).toBeVisible()
  await expect(history).toBeVisible()
  await expect(history).toContainText('7月27日周一')
  await expect(history).toContainText('完成方案评审')
  await expect(history).toContainText('推进中')
  await expect(history).toContainText('40%')
  await expect(history).toContainText('基线')
  await expect(history).toContainText('最新')
  await expect(rail).toContainText('项目分组')
  await expect(rail).toContainText('皇家全渠道定制项目')
  await expect(rail).toContainText('BU 内部事项')
  await expect(stage.getByLabel('演示事项简报')).toContainText('核心链路联调完成')
  await expect(stage.getByLabel('演示事项简报')).toContainText('上线窗口仍待确认')
  await expect(stage.getByLabel('演示事项简报')).toContainText('需协调 / 决策')
  await expect(stage.getByLabel('演示事项简报')).toContainText('下一步行动')
  const [railBox, presentationCardBox, historyBox] = await Promise.all([
    rail.boundingBox(),
    stage.locator('.presentation-card').boundingBox(),
    history.boundingBox()
  ])
  expect(railBox).not.toBeNull()
  expect(presentationCardBox).not.toBeNull()
  expect(historyBox).not.toBeNull()
  expect(Math.abs(railBox!.height - presentationCardBox!.height)).toBeLessThanOrEqual(1)
  expect(historyBox!.x).toBeGreaterThanOrEqual(presentationCardBox!.x + presentationCardBox!.width)

  const grouping = rail.getByRole('group', { name: '演示分组方式' })
  await grouping.getByRole('button', { name: '负责人' }).click()
  await expect(grouping.getByRole('button', { name: '负责人' })).toHaveAttribute('aria-pressed', 'true')
  await expect(rail).toContainText('石家乐')
  await expect(rail).toContainText('于峰')
  if (process.env.CAPTURE_KEY_MATTERS === '1') {
    await page.screenshot({ path: testInfo.outputPath('meeting-project-view.png'), fullPage: true })
  }

  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > document.documentElement.clientWidth
  )
  expect(overflow).toBe(false)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.presentation-layout')).toHaveCSS('overflow-y', 'auto')
  const mobileStageBox = await stage.boundingBox()
  const mobileHistoryBox = await history.boundingBox()
  expect(mobileStageBox).not.toBeNull()
  expect(mobileHistoryBox).not.toBeNull()
  expect(mobileStageBox!.x).toBeGreaterThanOrEqual(0)
  expect(mobileStageBox!.x + mobileStageBox!.width).toBeLessThanOrEqual(390)
  expect(mobileHistoryBox!.x).toBeGreaterThanOrEqual(0)
  expect(mobileHistoryBox!.x + mobileHistoryBox!.width).toBeLessThanOrEqual(390)
  expect(mobileHistoryBox!.y).toBeGreaterThanOrEqual(mobileStageBox!.y + mobileStageBox!.height)
  const mobileOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > document.documentElement.clientWidth
  )
  expect(mobileOverflow).toBe(false)
  await expect(page.getByRole('button', { name: '返回大事儿' })).toHaveCount(0)
})

test('周会演示模式可逐项浏览并现场补充待更新周报', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/key-matters-meeting')

  const stage = page.getByLabel('周会演示模式')
  await expect(stage).toBeVisible()
  const presentationRail = page.getByRole('complementary', { name: '演示分组导航' })
  await expect(presentationRail).toContainText('项目分组')
  await expect(presentationRail.getByRole('button', { name: /皇家全渠道定制项目/ })).toBeVisible()
  await presentationRail.getByRole('button', { name: '负责人' }).click()
  await expect(presentationRail).toContainText('负责人分组')
  await expect(presentationRail.getByRole('button', { name: /于峰/ })).toBeVisible()
  await presentationRail.getByRole('button', { name: /于峰/ }).click()
  await expect(stage).toContainText('运营平台数据质量治理')
  await presentationRail.getByRole('button', { name: '项目' }).click()
  await expect(presentationRail).toContainText('项目分组')
  await presentationRail.getByRole('button', { name: /皇家全渠道定制项目/ }).click()
  await expect(page.locator('.sidebar')).toBeHidden()
  await expect(page.locator('.top-header')).toBeHidden()
  await expect(page.locator('.page-toolbar')).toBeHidden()
  await expect(page.locator('body')).toHaveClass(/key-matters-presentation/)
  await expect(stage.getByRole('button', { name: /上一项：运营平台数据质量治理/ })).toBeVisible()
  await expect(stage.getByRole('button', { name: /下一项：运营平台数据质量治理/ })).toBeVisible()
  const [stageBox, cardBox] = await Promise.all([
    stage.boundingBox(),
    stage.locator('.presentation-card').boundingBox()
  ])
  expect(stageBox).not.toBeNull()
  expect(cardBox).not.toBeNull()
  expect(stageBox!.x + stageBox!.width).toBeLessThanOrEqual(1280)
  expect(stageBox!.y + stageBox!.height).toBeLessThanOrEqual(720)
  expect(cardBox!.x + cardBox!.width).toBeLessThanOrEqual(1280)
  await expect(page.locator('.presentation-layout')).toHaveCSS('overflow-y', 'auto')
  const readView = stage.locator('.presentation-read-view')
  await expect(readView).toHaveCSS('overflow-y', 'visible')
  const fullscreenOverflow = await page.evaluate(() =>
    document.documentElement.scrollHeight > document.documentElement.clientHeight
  )
  expect(fullscreenOverflow).toBe(false)
  await expect(stage.getByLabel('演示进度')).toHaveCount(0)
  const thumbnailBox = await stage.getByRole('button', { name: '跳转到第 1 项' }).boundingBox()
  expect(thumbnailBox).not.toBeNull()
  expect(thumbnailBox!.width).toBeLessThanOrEqual(40)
  expect(thumbnailBox!.height).toBeLessThanOrEqual(28)
  await expect(stage.getByRole('heading', { name: '皇家会员体系二期上线' })).toBeVisible()
  await expect(stage.getByLabel('演示事项简报')).toContainText('核心链路联调完成')
  if (process.env.CAPTURE_KEY_MATTERS === '1') {
    await page.screenshot({ path: testInfo.outputPath('presentation-updated.png'), fullPage: true })
  }
  await page.keyboard.press('ArrowRight')
  await expect(stage.getByRole('heading', { name: '运营平台数据质量治理' })).toBeVisible()
  await page.keyboard.press('ArrowLeft')
  await expect(stage.getByRole('heading', { name: '皇家会员体系二期上线' })).toBeVisible()
  await page.keyboard.press('ArrowLeft')
  await expect(stage.getByRole('heading', { name: '运营平台数据质量治理' })).toBeVisible()
  await page.keyboard.press('ArrowRight')
  await expect(stage.getByRole('heading', { name: '皇家会员体系二期上线' })).toBeVisible()

  await stage.getByRole('button', { name: '跳转到第 2 项' }).click()
  await expect(stage.getByRole('heading', { name: '运营平台数据质量治理' })).toBeVisible()
  await expect(stage.getByLabel('演示中更新周报')).toBeVisible()
  if (process.env.CAPTURE_KEY_MATTERS === '1') {
    await page.screenshot({ path: testInfo.outputPath('presentation-pending.png'), fullPage: true })
  }
  await expect(stage.getByLabel('演示中更新周报')).toContainText('结构化周进展')
  await expect(stage.getByRole('heading', { name: '本周成果' })).toBeVisible()
  await expect(stage.getByRole('heading', { name: '问题 / 风险' })).toBeVisible()
  await expect(stage.getByRole('heading', { name: '需协调 / 决策' })).toBeVisible()
  await expect(stage.getByRole('heading', { name: '下一步行动' })).toBeVisible()
  const editorCardBox = await stage.locator('.presentation-card').boundingBox()
  const editorFooterBox = await stage.locator('.presentation-card-footer').boundingBox()
  expect(editorCardBox).not.toBeNull()
  expect(editorFooterBox).not.toBeNull()
  const editView = stage.locator('.presentation-edit-view')
  await expect(editView).toHaveCSS('overflow-y', 'visible')
  const editorDimensions = await editView.evaluate(element => ({
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight
  }))
  expect(editorDimensions.scrollHeight).toBeLessThanOrEqual(editorDimensions.clientHeight)
  const textareaMinHeight = await stage.getByLabel('演示本周成果').evaluate(element =>
    Number.parseFloat(getComputedStyle(element).minHeight)
  )
  expect(textareaMinHeight).toBeGreaterThanOrEqual(58)
  await expect(stage.getByText('写清动作、目标和交付')).toBeVisible()

  await page.setViewportSize({ width: 1280, height: 600 })
  const presentationPage = page.locator('.presentation-layout')
  await expect(presentationPage).toHaveCSS('overflow-y', 'auto')
  const pageDimensions = await presentationPage.evaluate(element => ({
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight
  }))
  expect(pageDimensions.scrollHeight).toBeGreaterThan(pageDimensions.clientHeight)

  const nextActionInput = stage.getByLabel('演示下一步行动')
  await nextActionInput.scrollIntoViewIfNeeded()
  await expect(nextActionInput).toBeInViewport()
  if (process.env.CAPTURE_KEY_MATTERS === '1') {
    await page.screenshot({ path: testInfo.outputPath('presentation-pending-short.png'), fullPage: true })
  }
  await stage.getByLabel('演示本周成果').fill('完成数据质量规则梳理')
  await stage.getByLabel('演示问题与风险').fill('历史口径仍待确认')
  await nextActionInput.fill('完成首批规则上线')
  const footer = stage.locator('.presentation-card-footer')
  await footer.scrollIntoViewIfNeeded()
  await expect(footer).toBeInViewport()

  const requestPromise = page.waitForRequest(request =>
    request.url().includes('/api/key-matters/12/weekly-updates/')
      && request.method() === 'PUT'
  )
  await stage.getByRole('button', { name: /保存并下一项/ }).click()
  const request = await requestPromise
  expect(request.postDataJSON()).toMatchObject({
    status: '推进中',
    progress: 35,
    progressSummary: '完成数据质量规则梳理',
    issues: '历史口径仍待确认',
    nextWeekPlan: '完成首批规则上线'
  })

  await expect(page.getByRole('button', { name: '返回大事儿' })).toHaveCount(0)
  await page.setViewportSize({ width: 390, height: 844 })
  const mobileOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > document.documentElement.clientWidth
  )
  expect(mobileOverflow).toBe(false)
})

test('周会进度拖拽与轨道位置一致并可到达百分之百', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/key-matters-meeting')
  const stage = page.getByLabel('周会演示模式')
  await stage.getByRole('button', { name: '跳转到第 2 项' }).click()

  const progress = stage.locator('.presentation-inline-progress')
  const slider = progress.getByRole('slider', { name: '演示完成进度' })
  const track = progress.locator('.el-slider__runway')
  await expect(slider).toHaveAttribute('aria-valuenow', '35')
  const trackBox = await track.boundingBox()
  expect(trackBox).not.toBeNull()
  const y = trackBox!.y + trackBox!.height / 2

  await page.mouse.move(trackBox!.x + trackBox!.width * 0.35, y)
  await page.mouse.down()
  await page.mouse.move(trackBox!.x + trackBox!.width * 0.8, y, { steps: 8 })
  await page.mouse.up()
  await expect(slider).toHaveAttribute('aria-valuenow', '80')
  await expect(progress.locator('.presentation-progress-text')).toHaveText('80%')

  await page.mouse.move(trackBox!.x + trackBox!.width * 0.8, y)
  await page.mouse.down()
  await page.mouse.move(trackBox!.x + trackBox!.width, y, { steps: 4 })
  await page.mouse.up()
  await expect(slider).toHaveAttribute('aria-valuenow', '100')
  await expect(progress.locator('.presentation-progress-text')).toHaveText('100%')
  await expect(stage.locator('.presentation-meta-status .status-已完成')).toHaveClass(/active/)
})

test('周会快速导航内容超出时在卡片内部滚动', async ({ page }) => {
  const groupedMatters = Array.from({ length: 12 }, (_, index) => ({
    ...coreMatters[0],
    id: 200 + index,
    title: `周会滚动事项 ${index + 1}`,
    projectId: 100 + index,
    projectName: `滚动项目 ${index + 1}`,
    sortOrder: index
  }))
  await page.unroute('**/api/key-matters**')
  await page.route('**/api/key-matters**', route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/key-matters/access') return fulfillAdminKeyMatterAccess(route)
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: groupedMatters })
    })
  })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/key-matters-meeting')

  const rail = page.getByRole('complementary', { name: '演示分组导航' })
  const list = rail.locator('.presentation-group-list')
  await expect(list).toHaveCSS('overflow-y', 'auto')
  const dimensions = await list.evaluate(element => ({
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight
  }))
  expect(dimensions.scrollHeight).toBeGreaterThan(dimensions.clientHeight)

  const [railBox, listBox] = await Promise.all([rail.boundingBox(), list.boundingBox()])
  expect(railBox).not.toBeNull()
  expect(listBox).not.toBeNull()
  expect(listBox!.y + listBox!.height).toBeLessThanOrEqual(railBox!.y + railBox!.height)

  await list.evaluate(element => { element.scrollTop = element.scrollHeight })
  await expect.poll(() => list.evaluate(element => element.scrollTop)).toBeGreaterThan(0)
})

test('指定女性负责人在列表与周会中使用独立配色', async ({ page }) => {
  const femaleMatter = {
    ...coreMatters[0],
    ownerId: 18,
    ownerName: '丛宁'
  }
  await page.unroute('**/api/key-matters**')
  await page.route('**/api/key-matters**', route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/key-matters/access') return fulfillAdminKeyMatterAccess(route)
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: [femaleMatter] })
    })
  })

  await page.goto('/key-matters')
  await expect(page.locator('.owner-name.female')).toHaveText('丛宁')
  await expect(page.locator('.female-owner-name')).toHaveText('丛宁')

  await page.goto('/key-matters-meeting')
  await expect(page.locator('.presentation-avatar.female')).toBeVisible()
  await page.getByRole('complementary', { name: '演示分组导航' })
    .getByRole('button', { name: '负责人' }).click()
  await expect(page.locator('.presentation-group-avatar.female')).toBeVisible()
})

test('日历使用中文语言并保留 ISO 日期提交格式', async ({ page }) => {
  await page.goto('/key-matters')
  await page.getByRole('button', { name: '新增事项' }).click()
  const dialog = page.getByRole('dialog', { name: '新增大事儿' })
  await dialog.locator('.el-form-item', { hasText: '开始日期' }).locator('input').click()

  await expect(page.locator('.el-picker-panel:visible')).toContainText('年')
  await expect(page.locator('.el-picker-panel:visible')).toContainText('月')
})

test('里程碑默认收起并可展开月份时间线', async ({ page }) => {
  await page.goto('/key-matters')
  const milestoneRegion = page.getByLabel('列表顶部里程碑')
  await expect(milestoneRegion.getByRole('button', { name: '展开里程碑' })).toHaveAttribute('aria-expanded', 'false')
  await expect(page.getByLabel('里程碑时间线')).toHaveCount(0)

  await milestoneRegion.getByRole('button', { name: '展开里程碑' }).click()
  await expect(milestoneRegion.getByRole('button', { name: '收起里程碑' })).toHaveAttribute('aria-expanded', 'true')
  const timeline = page.getByLabel('里程碑时间线')
  const milestone = timeline.getByRole('button', { name: '2026-08-28，1个事项' })
  await expect(milestone).toBeVisible()
  await milestone.hover()
  await expect(page.getByRole('tooltip')).toContainText('皇家会员体系二期上线')
  await expect(page.getByRole('tooltip')).not.toContainText('运营平台数据质量治理')

  await page.setViewportSize({ width: 390, height: 844 })
  const mobileOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth > document.documentElement.clientWidth
  )
  expect(mobileOverflow).toBe(false)
})

test('新增事项提交完整负责人和计划周期', async ({ page }) => {
  await page.goto('/key-matters')
  await page.getByRole('button', { name: '新增事项' }).click()
  const dialog = page.getByRole('dialog', { name: '新增大事儿' })

  await dialog.getByLabel('事项标题').fill('客户体验专项治理')
  await dialog.getByLabel('负责人').press('ArrowDown')
  await page.getByRole('option', { name: '石家乐' }).click()
  await dialog.getByLabel('开始日期').fill('2026-08-05')
  await dialog.getByLabel('计划完成').fill('2026-08-31')

  const requestPromise = page.waitForRequest(request =>
    request.url().endsWith('/api/key-matters') && request.method() === 'POST'
  )
  await dialog.getByRole('button', { name: '保存' }).click()
  const request = await requestPromise
  expect(request.postDataJSON()).toMatchObject({
    title: '客户体验专项治理',
    ownerId: 7,
    startDate: '2026-08-05',
    plannedCompletionDate: '2026-08-31'
  })
})

test('已完成事项不再要求新增周进展', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await installCompletedMatterRoutes(page)

  await page.goto('/key-matters')
  const table = page.getByLabel('大事儿列表')
  const completedRow = table.locator('tr', { hasText: '完成事项无需周报' })
  await expect(completedRow).toBeVisible()
  await expect(completedRow.getByText('无需更新')).toBeVisible()
  await expect(completedRow.getByText('本周待更新')).toHaveCount(0)
  await expect(completedRow.getByRole('button', { name: '更新周进展' })).toHaveCount(0)

  const overview = page.getByLabel('大事儿操作栏').getByLabel('事项概览')
  await expect(overview.locator('.summary-cell.all .summary-value strong')).toHaveText('2')
  await expect(overview.locator('.summary-cell.pending .summary-value strong')).toHaveText('0')
  await expect(overview.locator('.summary-cell.pending .summary-value small')).toHaveText('1/1')

  await completedRow.getByText('完成事项无需周报').click()
  const detail = page.locator('.detail-content')
  await expect(detail.getByRole('button', { name: '更新周进展' })).toHaveCount(0)

  await page.goto('/key-matters-meeting')
  const stage = page.getByLabel('周会演示模式')
  await expect(stage).toBeVisible()
  await stage.getByRole('button', { name: '跳转到第 2 项' }).click()
  await expect(stage.getByRole('heading', { name: '完成事项无需周报' })).toBeVisible()

  const completedGroupButton = page.locator('.presentation-group-matters button', { hasText: '完成事项无需周报' })
  await expect(completedGroupButton.locator('i')).not.toHaveClass(/pending/)

  const completedQuickNav = stage.getByRole('button', { name: '跳转到第 2 项' })
  await expect(completedQuickNav).not.toHaveClass(/pending/)
  await expect(completedQuickNav.locator('.el-icon')).toHaveCount(0)

  await expect(stage.getByText('本周已完成，无需更新')).toBeVisible()
  await expect(stage.getByLabel('演示中更新周报')).toHaveCount(0)
  await expect(stage.getByRole('button', { name: /保存并下一项/ })).toHaveCount(0)
})

test('已完成事项保留历史修正入口且周会只读', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.unroute('**/api/key-matters**')
  await page.route('**/api/key-matters**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/key-matters/access') return fulfillAdminKeyMatterAccess(route)
    if (request.method() !== 'GET') {
      return route.fulfill({
        status: 405,
        contentType: 'application/json',
        body: JSON.stringify({ code: 405, message: '只读用例不支持写入 key-matters' })
      })
    }
    if (path === '/api/key-matters/14') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: completedMatterWithWeeklyUpdate })
      })
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: [completedMatterWithWeeklyUpdate] })
    })
  })

  await page.goto('/key-matters')
  const table = page.getByLabel('大事儿列表')
  const completedRow = table.locator('tr', { hasText: '完成事项保留历史修正入口' })
  await expect(completedRow).toBeVisible()
  await completedRow.getByText('完成事项保留历史修正入口').click()

  const detail = page.locator('.detail-content')
  await expect(detail).toBeVisible()
  await expect(detail.getByRole('button', { name: '更新周进展' })).toHaveCount(0)
  const history = detail.getByLabel('周进展记录')
  await expect(history.getByText('完成交付并通过验收')).toBeVisible()
  await expect(history.getByRole('button', { name: '编辑周进展' }).first()).toBeVisible()
  await expect(history.getByRole('button', { name: '删除周进展' }).first()).toBeVisible()

  await history.getByRole('button', { name: '编辑周进展' }).first().click()
  const weekly = page.getByRole('dialog', { name: '更新周进展' })
  await expect(weekly).toBeVisible()
  await expect(weekly.getByRole('textbox', { name: '本周成果' })).toHaveValue('完成交付并通过验收')
  await weekly.getByRole('button', { name: '取消' }).click()
  await expect(weekly).toHaveCount(0)

  await page.goto('/key-matters-meeting')
  const stage = page.getByLabel('周会演示模式')
  await expect(stage).toBeVisible()
  const brief = stage.getByLabel('演示事项简报')
  await expect(brief).toBeVisible()
  await expect(brief).toContainText('完成交付并通过验收')
  await expect(stage.getByRole('button', { name: '编辑周报' })).toHaveCount(0)
  await expect(stage.getByLabel('演示中更新周报')).toHaveCount(0)
  await expect(stage.getByRole('button', { name: /保存并下一项/ })).toHaveCount(0)
  await expect(stage.getByRole('button', { name: '查看详情' })).toBeVisible()
})

test('填写期间事项被完成后关闭新增表单并刷新状态', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  let matter12 = { ...coreMatters[1] }
  await page.unroute('**/api/key-matters**')
  await page.route('**/api/key-matters**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/key-matters/access') return fulfillAdminKeyMatterAccess(route)
    if (request.method() === 'PUT' && path.startsWith('/api/key-matters/12/weekly-updates/')) {
      matter12 = {
        ...matter12,
        status: '已完成',
        progress: 100,
        completedAt: '2026-08-21',
        currentWeekUpdated: false,
        latestUpdate: null,
        currentWeekUpdate: null,
        weeklyUpdates: []
      }
      return route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ code: 400, message: '已完成事项无需新增周进展' })
      })
    }
    if (path === '/api/key-matters/12') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: matter12 })
      })
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: [matter12] })
    })
  })

  await page.goto('/key-matters')
  const table = page.getByLabel('大事儿列表')
  const row = table.locator('tr', { hasText: '运营平台数据质量治理' })
  await expect(row.getByText('本周待更新')).toBeVisible()
  await row.getByRole('button', { name: '更新周进展' }).click()

  const weekly = page.getByRole('dialog', { name: '更新周进展' })
  await expect(weekly).toBeVisible()
  await weekly.getByRole('textbox', { name: '本周成果' }).fill('数据质量治理推进中')
  await weekly.getByRole('button', { name: '保存周进展' }).click()

  await expect(page.getByRole('dialog', { name: '更新周进展' })).toHaveCount(0)
  await expect(row.getByText('已完成')).toBeVisible()
  await expect(row.getByText('无需更新')).toBeVisible()
  await expect(row.getByText('本周待更新')).toHaveCount(0)
  // 回归断言：保留服务端返回的原因文案；抽屉关闭并刷新为「已完成/无需更新」行是新恢复行为
  await expect(page.getByText('已完成事项无需新增周进展')).toBeVisible()
})

test('演示填写期间事项被完成后仍定位原事项', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  const updatedMatter = { ...coreMatters[0] }
  const targetMatter = { ...coreMatters[1] }
  const otherMatter = {
    ...coreMatters[1],
    id: 14,
    title: '数据看板迭代事项',
    description: '演示期间的另一条待更新事项',
    sortOrder: 2
  }
  const completedTarget = {
    ...targetMatter,
    status: '已完成',
    progress: 100,
    completedAt: '2026-08-21',
    currentWeekUpdated: false,
    latestUpdate: null,
    currentWeekUpdate: null,
    weeklyUpdates: []
  }
  let targetCompleted = false

  await page.unroute('**/api/key-matters**')
  await page.route('**/api/key-matters**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/key-matters/access') return fulfillAdminKeyMatterAccess(route)
    if (request.method() === 'PUT' && path.startsWith('/api/key-matters/12/weekly-updates/')) {
      targetCompleted = true
      return route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({ code: 400, message: '已完成事项无需新增周进展' })
      })
    }
    if (path === '/api/key-matters/meeting') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          data: targetCompleted
            ? [updatedMatter, otherMatter, completedTarget]
            : [updatedMatter, targetMatter, otherMatter]
        })
      })
    }
    if (path === '/api/key-matters/12') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, data: targetCompleted ? completedTarget : targetMatter })
      })
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data: [updatedMatter, otherMatter] })
    })
  })

  await page.goto('/key-matters-meeting')
  const stage = page.getByLabel('周会演示模式')
  await expect(stage).toBeVisible()

  // 初始会议顺序为 [11, 12, 14]：通过快速缩略图定位到目标事项 12。
  await stage.getByRole('button', { name: '跳转到第 2 项' }).click()
  await expect(stage.getByRole('heading', { name: '运营平台数据质量治理' })).toBeVisible()
  await expect(stage.getByLabel('演示中更新周报')).toBeVisible()

  await stage.getByLabel('演示本周成果').fill('数据质量治理规则梳理完成')
  const saveRequest = page.waitForRequest(request =>
    request.url().includes('/api/key-matters/12/weekly-updates/')
      && request.method() === 'PUT'
  )
  await stage.getByRole('button', { name: /保存并下一项/ }).click()
  const request = await saveRequest
  expect(request.postDataJSON()).toMatchObject({
    progressSummary: '数据质量治理规则梳理完成'
  })

  // 保存失败后仍显示服务端原因，且演示重新定位到 id 12（而非 id 14）。
  await expect(page.getByText('已完成事项无需新增周进展')).toBeVisible()
  await expect(stage.getByRole('heading', { name: '运营平台数据质量治理' })).toBeVisible()
  await expect(stage.getByRole('heading', { name: '数据看板迭代事项' })).toHaveCount(0)
  await expect(stage.getByText('本周已完成，无需更新')).toBeVisible()
  await expect(stage.getByLabel('演示中更新周报')).toHaveCount(0)
})
