import { expect, test } from '@playwright/test'
import type { Route } from '@playwright/test'

// 业务线利润报表（/bl-profit）：列表渲染、业务线筛选、同步触发

const fulfill = (route: Route, data: unknown) => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, data })
})

interface ReportFixtureRow {
  yearMonth: string
  worktimeBusinessLineName: string
  businessLineId?: number | null
  revenue: number | null
  grossProfit: number | null
  grossProfitRate: number | null
  netProfit: number | null
  netProfitRate: number | null
  totalHours: number | null
}

const monthRow = (month: string, name: string, revenue: number, gross: number, net: number, hours: number): ReportFixtureRow => ({
  yearMonth: month,
  worktimeBusinessLineName: name,
  businessLineId: 1,
  revenue,
  grossProfit: gross,
  grossProfitRate: revenue ? Math.round((gross / revenue) * 1000) / 10 : null,
  netProfit: net,
  netProfitRate: revenue ? Math.round((net / revenue) * 1000) / 10 : null,
  totalHours: hours
})

const ytdRow = (name: string, revenue: number, gross: number, net: number, hours: number): ReportFixtureRow => ({
  yearMonth: 'YTD',
  worktimeBusinessLineName: name,
  businessLineId: 1,
  revenue,
  grossProfit: gross,
  grossProfitRate: revenue ? Math.round((gross / revenue) * 1000) / 10 : null,
  netProfit: net,
  netProfitRate: revenue ? Math.round((net / revenue) * 1000) / 10 : null,
  totalHours: hours
})

const reportFixture = {
  year: 2026,
  lines: [
    {
      businessLineId: 1,
      businessLineName: '会员通',
      groupName: '全域',
      months: [
        monthRow('2026-01', '会员通', 1000000, 400000, 300000, 10),
        monthRow('2026-02', '会员通', 3000000, 600000, 500000, 20)
      ],
      ytd: ytdRow('会员通', 4000000, 1000000, 800000, 30)
    },
    {
      businessLineId: 2,
      businessLineName: 'SAAS',
      groupName: '全域',
      months: [monthRow('2026-01', 'SAAS', 500000, -50000, -80000, 5)],
      ytd: ytdRow('SAAS', 500000, -50000, -80000, 5)
    }
  ],
  totalYtd: { ...ytdRow('合计', 4500000, 950000, 720000, 35), yearMonth: null, worktimeBusinessLineName: '合计' }
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' }))
  })
  await page.route('**/api/finance/bl-profit/sync-logs**', route => fulfill(route, []))
})

test('列表展示业务线月行、YTD 小计与总计', async ({ page }) => {
  await page.route('**/api/finance/bl-profit?**', route => fulfill(route, reportFixture))

  await page.goto('/bl-profit')
  const table = page.locator('.profit-table')
  await expect(table).toBeVisible()

  // 表头含月列
  await expect(table.locator('thead')).toContainText('月')
  await expect(table.locator('thead')).toContainText('营业收入(万)')
  await expect(table.locator('thead')).toContainText('考核毛利率')

  // 月行：会员通两行 + SAAS 一行；金额换算为万
  await expect(table).toContainText('2026-01')
  await expect(table).toContainText('2026-02')
  const hytRow = table.locator('tr.month-row', { hasText: '会员通' }).filter({ hasText: '2026-02' })
  await expect(hytRow).toContainText('300') // 营收 300 万
  await expect(hytRow).toContainText('60') // 毛利 60 万

  // YTD 小计行
  const ytdRows = table.locator('tr.ytd-row')
  await expect(ytdRows).toHaveCount(2)
  await expect(ytdRows.first()).toContainText('YTD')
  await expect(ytdRows.first()).toContainText('400') // 会员通 YTD 营收 400 万

  // 总计行
  const total = table.locator('tr.grand-total-row')
  await expect(total).toContainText('合计')
  await expect(total).toContainText('450') // 450 万
  await expect(total).toContainText('21.1%') // 毛利率 95/450

  // 负数红显
  await expect(table.locator('td.negative').first()).toBeVisible()
})

test('业务线筛选 pill 过滤行', async ({ page }) => {
  await page.route('**/api/finance/bl-profit?**', route => fulfill(route, reportFixture))

  await page.goto('/bl-profit')
  const table = page.locator('.profit-table')
  await expect(table).toBeVisible()

  await page.locator('.filter-pill', { hasText: 'SAAS' }).click()
  await expect(table.locator('tr.month-row')).toHaveCount(1)
  await expect(table).toContainText('SAAS')
  await expect(table.locator('tbody')).not.toContainText('会员通')

  // 重置为全部
  await page.locator('.filter-pill', { hasText: '全部业务线' }).click()
  await expect(table.locator('tr.month-row')).toHaveCount(3)
})

test('同步按钮触发整年同步并刷新', async ({ page }) => {
  let syncedYear: number | null = null
  await page.route('**/api/finance/bl-profit?**', route => fulfill(route, reportFixture))
  await page.route('**/api/finance/bl-profit/sync', route => {
    syncedYear = route.request().postDataJSON().year
    return fulfill(route, [
      { id: 1, syncType: 'bl_profit', scope: '2026-01', status: 'success', totalCount: 15, upsertCount: 15, pendingCount: 0, message: 'ok', triggeredBy: 'manual', startedAt: null, finishedAt: '2026-09-14 10:00:00' }
    ])
  })

  await page.goto('/bl-profit')
  await expect(page.locator('.profit-table')).toBeVisible()

  await page.getByRole('button', { name: '同步工时系统' }).click()
  await expect.poll(() => syncedYear).toBe(2026)
  await expect(page.locator('.el-message').last()).toContainText('同步完成')
})

test('空数据引导同步', async ({ page }) => {
  await page.route('**/api/finance/bl-profit?**', route => fulfill(route, { year: 2026, lines: [], totalYtd: null }))

  await page.goto('/bl-profit')
  await expect(page.getByText('暂无数据，点击右上角「同步工时系统」拉取业务线利润报表')).toBeVisible()
})
