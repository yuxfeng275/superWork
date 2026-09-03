import { expect, test } from '@playwright/test'
import type { Locator, Page, Route } from '@playwright/test'

interface PendingContract {
  id: number
  brand?: string | null
  customer?: string | null
  contractNo?: string | null
  detailNo?: string | null
  contractName?: string | null
  itemDesc?: string | null
  bizLineRaw?: string | null
  bizLineId?: number | null
  receivableAmount?: number | null
  saleMonth?: string | null
  pending?: number
}

// ---------- summary fixtures（对齐后端 RevenueDeliverySummaryVO，金额元 / 人月） ----------

/** 窗口快照：laborProfit/grossProfit 为服务端口径的中间值，前端只展示 trueProfit 系列 */
const window = (o: Record<string, unknown>) => ({
  delivered: 0, estimated: 0, projectHours: 0, projectLaborCost: 0, estimatedLaborCost: 0,
  salesHours: 0, salesCost: 0, allocatedSalesHours: 0, allocatedSalesCost: 0,
  unallocatedSalesHours: 0, unallocatedSalesCost: 0,
  otherCosts: { partner: 0, server: 0, other: 0, total: 0 },
  laborProfit: 0, grossProfit: 0, grossRate: null, trueProfit: 0, trueProfitRate: null,
  ...o
})

const royalProject = (includeEstimate: boolean) => includeEstimate
  ? {
    projectId: 101, name: '皇家项目', isAggregate: false, oaContract: 2000000,
    h1: window({
      delivered: 800000, estimated: 300000, projectHours: 24, projectLaborCost: 360000, estimatedLaborCost: 80000,
      allocatedSalesHours: 1, allocatedSalesCost: 10000,
      otherCosts: { partner: 40000, server: 20000, other: 10000, total: 70000 },
      grossProfit: 590000, grossRate: 53.64, trueProfit: 580000, trueProfitRate: 52.73
    }),
    h2: window({
      delivered: 1000000, estimated: 700000, projectHours: 36, projectLaborCost: 540000, estimatedLaborCost: 120000,
      allocatedSalesHours: 2, allocatedSalesCost: 20000,
      otherCosts: { partner: 60000, server: 30000, other: 10000, total: 100000 },
      grossProfit: 940000, grossRate: 55.29, trueProfit: 920000, trueProfitRate: 54.12
    }),
    ytd: window({
      delivered: 1800000, estimated: 1000000, projectHours: 60, projectLaborCost: 900000, estimatedLaborCost: 200000,
      allocatedSalesHours: 3, allocatedSalesCost: 30000,
      otherCosts: { partner: 100000, server: 50000, other: 20000, total: 170000 },
      grossProfit: 1530000, grossRate: 54.64, trueProfit: 1500000, trueProfitRate: 53.57
    })
  }
  : {
    projectId: 101, name: '皇家项目', isAggregate: false, oaContract: 2000000,
    h1: window({
      delivered: 800000, estimated: 0, projectHours: 24, projectLaborCost: 360000, estimatedLaborCost: 0,
      allocatedSalesHours: 1, allocatedSalesCost: 10000,
      otherCosts: { partner: 40000, server: 20000, other: 10000, total: 70000 },
      grossProfit: 370000, grossRate: 46.25, trueProfit: 360000, trueProfitRate: 45
    }),
    h2: window({
      delivered: 1000000, estimated: 0, projectHours: 36, projectLaborCost: 540000, estimatedLaborCost: 0,
      allocatedSalesHours: 2, allocatedSalesCost: 20000,
      otherCosts: { partner: 60000, server: 30000, other: 10000, total: 100000 },
      grossProfit: 360000, grossRate: 36, trueProfit: 340000, trueProfitRate: 34
    }),
    ytd: window({
      delivered: 1800000, estimated: 0, projectHours: 60, projectLaborCost: 900000, estimatedLaborCost: 0,
      allocatedSalesHours: 3, allocatedSalesCost: 30000,
      otherCosts: { partner: 100000, server: 50000, other: 20000, total: 170000 },
      grossProfit: 730000, grossRate: 40.56, trueProfit: 700000, trueProfitRate: 38.89
    })
  }

const speedoProject = (includeEstimate: boolean) => includeEstimate
  ? {
    projectId: 102, name: 'Speedo', isAggregate: false, oaContract: 800000,
    h1: window({
      delivered: 400000, estimated: 100000, projectHours: 6, projectLaborCost: 90000, estimatedLaborCost: 30000,
      otherCosts: { partner: 0, server: 40000, other: 0, total: 40000 },
      grossProfit: 340000, grossRate: 68, trueProfit: 340000, trueProfitRate: 68
    }),
    h2: window({
      delivered: 200000, estimated: 300000, projectHours: 6, projectLaborCost: 90000, estimatedLaborCost: 90000,
      otherCosts: { partner: 0, server: 60000, other: 0, total: 60000 },
      grossProfit: 260000, grossRate: 52, trueProfit: 260000, trueProfitRate: 52
    }),
    ytd: window({
      delivered: 600000, estimated: 400000, projectHours: 12, projectLaborCost: 180000, estimatedLaborCost: 120000,
      otherCosts: { partner: 0, server: 100000, other: 0, total: 100000 },
      grossProfit: 600000, grossRate: 60, trueProfit: 600000, trueProfitRate: 60
    })
  }
  : {
    projectId: 102, name: 'Speedo', isAggregate: false, oaContract: 800000,
    h1: window({
      delivered: 400000, estimated: 0, projectHours: 6, projectLaborCost: 90000, estimatedLaborCost: 0,
      otherCosts: { partner: 0, server: 40000, other: 0, total: 40000 },
      grossProfit: 270000, grossRate: 67.5, trueProfit: 270000, trueProfitRate: 67.5
    }),
    h2: window({
      delivered: 200000, estimated: 0, projectHours: 6, projectLaborCost: 90000, estimatedLaborCost: 0,
      otherCosts: { partner: 0, server: 60000, other: 0, total: 60000 },
      grossProfit: 50000, grossRate: 25, trueProfit: 50000, trueProfitRate: 25
    }),
    ytd: window({
      delivered: 600000, estimated: 0, projectHours: 12, projectLaborCost: 180000, estimatedLaborCost: 0,
      otherCosts: { partner: 0, server: 100000, other: 0, total: 100000 },
      grossProfit: 320000, grossRate: 53.33, trueProfit: 320000, trueProfitRate: 53.33
    })
  }

const customLine = (includeEstimate: boolean) => {
  const lineSales = {
    salesHours: 10, salesCost: 150000,
    salesAllocatedHours: 3, salesAllocatedCost: 30000,
    salesUnallocatedHours: 7, salesUnallocatedCost: 120000,
    salesUnallocatedDetail: [{ reason: 'POOL_NO_EVIDENCE', label: '商机集合无成单证据', cost: 120000 }]
  }
  const totalsWindows = includeEstimate
    ? {
      h1: window({
        delivered: 1200000, estimated: 400000, projectHours: 30, projectLaborCost: 450000, estimatedLaborCost: 110000,
        salesHours: 4, salesCost: 60000, allocatedSalesHours: 1, allocatedSalesCost: 10000,
        unallocatedSalesHours: 3, unallocatedSalesCost: 50000,
        otherCosts: { partner: 40000, server: 60000, other: 10000, total: 110000 },
        grossProfit: 870000, grossRate: 54.38, trueProfit: 870000, trueProfitRate: 54.38
      }),
      h2: window({
        delivered: 1200000, estimated: 1000000, projectHours: 42, projectLaborCost: 630000, estimatedLaborCost: 210000,
        salesHours: 6, salesCost: 90000, allocatedSalesHours: 2, allocatedSalesCost: 20000,
        unallocatedSalesHours: 4, unallocatedSalesCost: 70000,
        otherCosts: { partner: 60000, server: 90000, other: 10000, total: 160000 },
        grossProfit: 1110000, grossRate: 50.45, trueProfit: 1110000, trueProfitRate: 50.45
      }),
      ytd: window({
        delivered: 2400000, estimated: 1400000, projectHours: 72, projectLaborCost: 1080000, estimatedLaborCost: 320000,
        salesHours: 10, salesCost: 150000, allocatedSalesHours: 3, allocatedSalesCost: 30000,
        unallocatedSalesHours: 7, unallocatedSalesCost: 120000,
        otherCosts: { partner: 100000, server: 150000, other: 20000, total: 270000 },
        grossProfit: 1980000, grossRate: 52.11, trueProfit: 1980000, trueProfitRate: 52.11
      })
    }
    : {
      h1: window({
        delivered: 1200000, estimated: 0, projectHours: 30, projectLaborCost: 450000, estimatedLaborCost: 0,
        salesHours: 4, salesCost: 60000, allocatedSalesHours: 1, allocatedSalesCost: 10000,
        unallocatedSalesHours: 3, unallocatedSalesCost: 50000,
        otherCosts: { partner: 40000, server: 60000, other: 10000, total: 110000 },
        grossProfit: 580000, grossRate: 48.33, trueProfit: 580000, trueProfitRate: 48.33
      }),
      h2: window({
        delivered: 1200000, estimated: 0, projectHours: 42, projectLaborCost: 630000, estimatedLaborCost: 0,
        salesHours: 6, salesCost: 90000, allocatedSalesHours: 2, allocatedSalesCost: 20000,
        unallocatedSalesHours: 4, unallocatedSalesCost: 70000,
        otherCosts: { partner: 60000, server: 90000, other: 10000, total: 160000 },
        grossProfit: 320000, grossRate: 26.67, trueProfit: 320000, trueProfitRate: 26.67
      }),
      ytd: window({
        delivered: 2400000, estimated: 0, projectHours: 72, projectLaborCost: 1080000, estimatedLaborCost: 0,
        salesHours: 10, salesCost: 150000, allocatedSalesHours: 3, allocatedSalesCost: 30000,
        unallocatedSalesHours: 7, unallocatedSalesCost: 120000,
        otherCosts: { partner: 100000, server: 150000, other: 20000, total: 270000 },
        grossProfit: 900000, grossRate: 37.5, trueProfit: 900000, trueProfitRate: 37.5
      })
    }
  return {
    businessLineId: 1,
    businessLineName: '全渠道云鹿定制',
    ...lineSales,
    projects: [royalProject(includeEstimate), speedoProject(includeEstimate)],
    totals: {
      projectId: null,
      name: '合计',
      isAggregate: false,
      oaContract: 2800000,
      ...totalsWindows
    }
  }
}

const memberLine = () => {
  const zero = window({})
  return {
    businessLineId: 3,
    businessLineName: '会员通',
    salesHours: 0, salesCost: 0, salesAllocatedHours: 0, salesAllocatedCost: 0,
    salesUnallocatedHours: 0, salesUnallocatedCost: 0, salesUnallocatedDetail: [],
    projects: [{
      projectId: null, name: '项目集', isAggregate: true, oaContract: 0,
      h1: zero, h2: zero, ytd: zero
    }],
    totals: { projectId: null, name: '合计', isAggregate: false, oaContract: 0, h1: zero, h2: zero, ytd: zero }
  }
}

const makeSummary = (includeEstimate: boolean) => ({
  year: 2026,
  includeEstimate,
  lines: [customLine(includeEstimate), memberLine()],
  overview: includeEstimate
    ? {
      includeEstimate: true,
      totalOaContract: 2800000, totalDelivered: 2400000, totalEstimated: 1400000,
      totalLaborCost: 1550000, totalAllocatedSalesCost: 30000, totalUnallocatedSalesCost: 120000,
      totalOtherCost: 270000, totalProfit: 1980000, profitRate: 52.11,
      totalTrueProfit: 1980000, trueProfitRate: 52.11,
      salesUnallocatedDetail: [{ reason: 'POOL_NO_EVIDENCE', label: '商机集合无成单证据', cost: 120000 }]
    }
    : {
      includeEstimate: false,
      totalOaContract: 2800000, totalDelivered: 2400000, totalEstimated: 0,
      totalLaborCost: 1230000, totalAllocatedSalesCost: 30000, totalUnallocatedSalesCost: 120000,
      totalOtherCost: 270000, totalProfit: 900000, profitRate: 37.5,
      totalTrueProfit: 900000, trueProfitRate: 37.5,
      salesUnallocatedDetail: [{ reason: 'POOL_NO_EVIDENCE', label: '商机集合无成单证据', cost: 120000 }]
    }
})

/** 业务线级未落项目合同（福田等）：叠加字段 + 保持窗口/合计口径自洽（金额元） */
const withLineLevelContract = (summary: ReturnType<typeof makeSummary>, amount = 4650): ReturnType<typeof makeSummary> => {
  const clone = structuredClone(summary)
  const line = clone.lines[0]
  line.lineUnallocatedContract = amount
  line.lineUnallocatedDelivered = amount
  line.lineUnallocatedProfit = amount
  if (line.totals) {
    line.totals.lineUnallocatedContract = amount
    line.totals.lineUnallocatedDelivered = amount
    line.totals.lineUnallocatedProfit = amount
    line.totals.ytd = {
      ...line.totals.ytd,
      delivered: (line.totals.ytd?.delivered || 0) + amount
    }
  }
  const ov = clone.overview
  ov.totalLineUnallocatedContract = amount
  ov.totalLineUnallocatedDelivered = amount
  ov.totalLineUnallocatedProfit = amount
  ov.totalOaContract = (ov.totalOaContract || 0) + amount
  ov.totalDelivered = (ov.totalDelivered || 0) + amount
  ov.totalTrueProfit = (ov.totalTrueProfit || 0) + amount
  return clone
}

const matrix = {
  year: 2026,
  months: [],
  lines: [],
  monthTotals: [],
  grandTotal: { hours: 0, cost: 0, source: null },
  overview: { totalHours: 0, projectHours: 0, salesHours: 0, totalCost: 0, closedMonthCount: 0 }
}

const fulfill = (route: Route, data: unknown) => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, data })
})

// 每个测试独立追踪：summary 请求（含 includeEstimate 参数）与合同待映射清单
const summaryRequests: string[] = []
let pendingContracts: PendingContract[] = []
const pendingFixture = (contracts: PendingContract[]) => {
  pendingContracts = contracts
  return contracts
}
const mappedFixture = []
test.beforeEach(async ({ page }) => {
  summaryRequests.length = 0
  pendingContracts = []

  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' }))
  })

  await page.route('**/api/revenue/matrix**', route => fulfill(route, matrix))

  await page.route('**/api/revenue/delivery/summary**', route => {
    const url = new URL(route.request().url())
    summaryRequests.push(url.toString())
    const includeEstimate = url.searchParams.get('includeEstimate') === 'true'
    return fulfill(route, makeSummary(includeEstimate))
  })

  await page.route('**/api/revenue/delivery-plans**', route => fulfill(route, []))
  await page.route('**/api/revenue/estimates/unit-price**', route => fulfill(route, { unitPrice: 22790.7 }))
  await page.route('**/api/revenue/other-costs**', route => fulfill(route, []))
  await page.route('**/api/revenue/pending', route => fulfill(route, { worklog: [], cost: [] }))
  await page.route('**/api/revenue/sales-projects', route => fulfill(route, []))
  await page.route('**/api/revenue/opportunity-options', route => fulfill(route, []))

  await page.route('**/api/revenue/contracts/pending**', route => {
    if (/\/pending\/\d+\/resolve$/.test(route.request().url())) {
      pendingContracts = []
      return fulfill(route, null)
    }
    const fixture = new URL(route.request().url()).searchParams.get('fixture')
    if (fixture === 'futian') return fulfill(route, [{ id: 31, brand: '北汽福田', contractNo: 'FT-2026-002', detailNo: '20260902000031', contractName: '北汽福田定制开发服务合同', bizLineRaw: '全域-全渠道-全域云鹿定制', bizLineId: 1, receivableAmount: 4650, pending: 1 }])
    if (fixture === 'flyhigh') return fulfill(route, [{ id: 21, brand: '飞鹤', contractNo: 'FH-2026-001', detailNo: '3957852593046113680', contractName: '飞鹤 2026 年度会员运营服务合同', bizLineRaw: '全域-全渠道-全域云鹿定制', bizLineId: 1, receivableAmount: 250000, pending: 1 }])
    return fulfill(route, pendingContracts)
  })
  await page.route('**/api/revenue/contracts/mapped**', route => fulfill(route, []))

  await page.route('**/api/business-lines**', route => fulfill(route, {
    records: [
      { id: 1, name: '全渠道云鹿定制' },
      { id: 2, name: 'SAAS' },
      { id: 3, name: '会员通' }
    ],
    total: 3
  }))
  await page.route('**/api/projects**', route => fulfill(route, {
    records: [
      { id: 101, name: '皇家项目', businessLineId: 1 },
      { id: 102, name: 'Speedo', businessLineId: 1 },
      { id: 15, name: '黄天鹅', businessLineId: 2 }
    ],
    total: 3
  }))
})

const openDeliveryPanel = async (page: Page) => {
  await page.goto('/revenue')
  await page.getByRole('tab', { name: '交付与利润' }).click()
  const panel = page.getByRole('tabpanel', { name: '交付与利润' })
  await expect(panel.locator('.matrix-table')).toBeVisible()
  return panel
}

const dataRow = (panel: Locator, name: string) =>
  panel.locator('.matrix-table tbody tr', { hasText: name })

// 概览卡固定顺序：OA 合同总额 / 已交付 / 预估交付 / 人工成本 / 其他成本 / 真实利润 / 真实利润率
const overviewCell = (panel: Locator, index: number) =>
  panel.locator('.overview-strip .overview-cell').nth(index)

// 每段 9 列：已交付/预估交付/工时/工时成本/销售工时/销售成本/其他成本/利润/利润率；
// 全行（含业务线/项目/OA 三列）ytd 段起始列 = 21
const YTD = { delivered: 21, estimated: 22, hours: 23, labor: 24, salesHours: 25, salesCost: 26, other: 27, profit: 28, rate: 29 }

test('交付汇总表渲染业务线分组、项目行、销售拆分、h1/h2/ytd 数值、合计与概览卡', async ({ page }) => {
  const panel = await openDeliveryPanel(page)
  const table = panel.locator('.matrix-table')

  // 段分组与业务线/项目行
  await expect(table).toContainText('上半年 H1')
  await expect(table).toContainText('下半年 H2')
  await expect(table).toContainText('全年 YTD')
  await expect(table).toContainText('皇家项目')
  await expect(table).toContainText('Speedo')
  await expect(table).toContainText('项目集')
  await expect(table).toContainText('全渠道云鹿定制')
  await expect(table).toContainText('会员通')
  await expect(table).toContainText('合计')
  await expect(table).toContainText('合计')

  // 销售口径说明：项目行=成单销售；汇总行=未分配销售（不分摊）
  const legend = panel.locator('.matrix-legend')
  await expect(legend).toContainText('成单销售')
  await expect(legend).toContainText('未分配销售')
  await expect(legend).toContainText('不分摊到项目')

  // 皇家项目行（真实项目利润 = 扣成单销售成本后）
  const royalRow = dataRow(panel, '皇家项目')
  const cell = (index: number) => royalRow.locator('td').nth(index)
  await expect(cell(2)).toContainText('200')          // OA 合同总额（万）
  await expect(cell(YTD.delivered)).toContainText('180')
  await expect(cell(YTD.estimated)).toContainText('100')
  await expect(cell(YTD.hours)).toContainText('60')
  await expect(cell(YTD.labor)).toContainText('90')
  await expect(cell(YTD.salesHours)).toContainText('3')   // 成单销售工时（已分配）
  await expect(cell(YTD.salesCost)).toContainText('3')    // 成单销售成本（已分配）
  await expect(cell(YTD.other)).toContainText('17')
  await expect(cell(YTD.profit)).toContainText('150')     // 真实项目利润（万）
  await expect(cell(YTD.rate)).toContainText('53.57%')

  // 业务线合计行：未分配销售展示 + totals 真实利润
  const lineTotalRow = table.locator('tbody tr.line-total-row', { hasText: '全渠道云鹿定制' })
  await expect(lineTotalRow).toContainText('未分配销售 7 人月 · 12 万')
  await expect(lineTotalRow).toContainText('仅扣业务线利润')
  const totalCell = (index: number) => lineTotalRow.locator('td').nth(index)
  await expect(totalCell(2)).toContainText('280')
  await expect(totalCell(YTD.salesHours)).toContainText('7')     // 未分配销售工时
  await expect(totalCell(YTD.salesCost)).toContainText('12')     // 未分配销售成本
  await expect(totalCell(YTD.labor)).toContainText('108')
  await expect(totalCell(YTD.other)).toContainText('27')
  await expect(totalCell(YTD.profit)).toContainText('198')       // 业务线利润
  await expect(totalCell(YTD.rate)).toContainText('52.11%')

  // 概览卡（含预估默认值）
  const overview = panel.locator('.overview-strip')
  await expect(overview).toContainText('OA 合同总额')
  await expect(overview).toContainText('真实利润率')
  await expect(overviewCell(panel, 0)).toContainText('280')
  await expect(overviewCell(panel, 1)).toContainText('240')
  await expect(overviewCell(panel, 2)).toContainText('140')
  await expect(overviewCell(panel, 3)).toContainText('155')   // 人工成本含预估工时与销售成本
  await expect(overviewCell(panel, 4)).toContainText('27')
  await expect(overviewCell(panel, 5)).toContainText('198')
  await expect(overviewCell(panel, 6)).toContainText('52.11%')

  // 会员通聚合行也有操作按钮（预估交付/其他成本）
  const memberRow = dataRow(panel, '项目集')
  await expect(memberRow.getByRole('button', { name: '预估交付' })).toBeVisible()

  // 点击项目利润单元格：drawer 展示成单销售成本扣减
  await royalRow.locator('td').nth(YTD.profit).click()
  const drawer = page.getByRole('dialog')
  await expect(drawer).toContainText('减 · 成单销售成本')
  await expect(drawer).toContainText('减 · 预估工时成本（含预估口径）')
  await expect(drawer).toContainText('真实利润')
  await page.keyboard.press('Escape')

  // 业务线合计行 drawer：展示全额销售成本（成单+未分配）扣减
  await lineTotalRow.locator('td').nth(YTD.profit).click()
  const totalsDrawer = page.getByRole('dialog')
  await expect(totalsDrawer).toContainText('减 · 销售成本（含成单+未分配）')
  await expect(totalsDrawer).toContainText('业务线利润')
  await expect(totalsDrawer).toContainText('商机集合无成单证据')   // 未分配原因
})

test('含预估开关联动：切换口径重新拉 summary 且预估列/利润联动', async ({ page }) => {
  const panel = await openDeliveryPanel(page)
  const royalRow = dataRow(panel, '皇家项目')

  // 默认含预估
  await expect(overviewCell(panel, 5)).toContainText('198')
  await expect(royalRow.locator('td').nth(YTD.profit)).toContainText('150')
  expect(summaryRequests[summaryRequests.length - 1]).toContain('includeEstimate=true')

  // 切「只看实际」：重新请求 includeEstimate=false，预估列隐藏、利润变化
  await panel.getByRole('button', { name: '只看实际' }).click()
  await expect(overviewCell(panel, 5)).toContainText('90')
  await expect(overviewCell(panel, 6)).toContainText('37.5%')
  await expect(royalRow.locator('td').nth(YTD.profit)).toContainText('70')
  await expect(royalRow.locator('td').nth(YTD.estimated)).toHaveText('—')
  expect(summaryRequests[summaryRequests.length - 1]).toContain('includeEstimate=false')

  // 切回「含预估」
  await panel.getByRole('button', { name: '含预估' }).click()
  await expect(overviewCell(panel, 5)).toContainText('198')
  await expect(royalRow.locator('td').nth(YTD.estimated)).toContainText('100')
  expect(summaryRequests[summaryRequests.length - 1]).toContain('includeEstimate=true')
})

test('预估交付批量新增：dialog 加行并断言 batch POST payload', async ({ page }) => {
  const panel = await openDeliveryPanel(page)
  const royalRow = dataRow(panel, '皇家项目')

  await royalRow.getByRole('button', { name: '预估交付' }).click()
  const dialog = page.getByRole('dialog', { name: /预估交付计划/ })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('历史完结单价：2.28')   // estimates/unit-price 提示

  const grid = dialog.getByLabel('按月批量新增预估交付')
  const batchRow = (index: number) => grid.locator('.plan-batch-row').nth(index)
  const chooseMonth = async (rowIndex: number, label: string) => {
    await batchRow(rowIndex).locator('.el-select__wrapper').click()
    await page.getByRole('option', { name: label }).last().click()
  }
  const setAmount = async (rowIndex: number, value: string) => {
    const input = batchRow(rowIndex).locator('.el-input-number input').first()
    await input.click()
    await input.press('Meta+A')
    await input.pressSequentially(value)
    await input.press('Tab')
  }

  // 两行：同月（08月）不同金额
  await chooseMonth(0, '08月')
  await setAmount(0, '60')
  await dialog.getByRole('button', { name: '+ 添加一条' }).click()
  await chooseMonth(1, '08月')
  await setAmount(1, '40')

  const posted: Array<{ businessLineId: number; projectId: number; year: number; rows: Array<{ yearMonth: string; amountYuan: number; personMonths: number }> }> = []
  await page.route('**/api/revenue/delivery-plans/batch', route => {
    posted.push(route.request().postDataJSON())
    return fulfill(route, [])
  })
  await dialog.getByRole('button', { name: '保存批量新增' }).click()

  await expect(page.locator('.el-message')).toContainText('已保存 2 条预估交付计划')
  expect(posted).toHaveLength(1)
  expect(posted[0]).toMatchObject({ businessLineId: 1, projectId: 101, year: 2026 })
  expect(posted[0].rows).toHaveLength(2)
  expect(posted[0].rows.map(row => row.yearMonth)).toEqual(['2026-08', '2026-08'])
  expect(posted[0].rows[0].amountYuan).toBe(600000)
  expect(posted[0].rows[1].amountYuan).toBe(400000)
})

test('待映射黄天鹅合同切换业务线后映射到 SAAS 项目', async ({ page }) => {
  const fixture: PendingContract = {
    id: 21,
    brand: '黄天鹅',
    contractNo: 'HT-21',
    contractName: '黄天鹅待映射合同',
    bizLineId: 1,
    projectId: null,
    receivableAmount: 250000,
    pending: 1
  }
  let currentPending: PendingContract[] = [fixture]
  await page.unroute('**/api/revenue/contracts/pending**')
  await page.route('**/api/revenue/contracts/pending**', async route => {
    if (route.request().method() === 'POST') {
      currentPending = []
      return fulfill(route, null)
    }
    return fulfill(route, currentPending)
  })
  await page.goto('/revenue')
  await page.locator('#tab-pending').evaluate(element => (element as HTMLElement).click())
  const panel = page.getByRole('tabpanel', { name: '待映射与销售项目' })
  const pendingSection = panel.locator('.pending-section').filter({ hasText: '合同待映射' })
  const pendingTable = pendingSection.locator('.data-table')
  await expect(pendingTable).toContainText('HT-21')
  const row = pendingTable.locator('tr', { hasText: 'HT-21' })
  const lineSelect = row.locator('.el-select').nth(0)
  const projectSelect = row.locator('.el-select').nth(1)
  await lineSelect.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: 'SAAS', exact: true }).last().evaluate(element => (element as HTMLElement).click())
  await expect(projectSelect.locator('.el-select__wrapper')).toContainText('业务线级')
  await projectSelect.locator('.el-select__wrapper').click()
  await page.getByRole('option', { name: '黄天鹅', exact: true }).last().evaluate(element => (element as HTMLElement).click())
  const resolveRequest = page.waitForRequest(request => /\/api\/revenue\/contracts\/pending\/21\/resolve$/.test(request.url()) && request.method() === 'POST')
  await row.getByRole('button', { name: '确定' }).click()
  const request = await resolveRequest
  expect(request.method()).toBe('POST')
  expect(request.postDataJSON()).toEqual({ businessLineId: 2, projectId: 15 })
  await expect(page.locator('.el-message').last()).toContainText('合同已映射')
  await expect(pendingTable).toContainText('暂无待映射合同')
})



test('业务线级合同（福田定制不落项目）在业务线合计与整表合计可见，含交付日期口径提示', async ({ page }) => {
  // 定制线叠加业务线级未落项目合同（4650 元 = 0.47 万）
  await page.route('**/api/revenue/delivery/summary**', route => {
    const url = new URL(route.request().url())
    const includeEstimate = url.searchParams.get('includeEstimate') === 'true'
    return fulfill(route, withLineLevelContract(makeSummary(includeEstimate)))
  })
  const panel = await openDeliveryPanel(page)
  const table = panel.locator('.matrix-table')

  // 业务线合计行：线级合同说明 + OA 列含线级合同金额
  const lineTotalRow = table.locator('tbody tr.line-total-row', { hasText: '全渠道云鹿定制' })
  await expect(lineTotalRow).toContainText('业务线级合同（未落具体项目）')
  await expect(lineTotalRow).toContainText('合同 0.47 万')
  await expect(lineTotalRow).toContainText('已交付 0.47 万')
  await expect(lineTotalRow.locator('td').nth(2)).toContainText('280.46') // OA=280万+0.47万（0.465 浮点进位显示 280.46）

  // 整表合计行：线级合同不消失
  const grandRow = table.locator('tbody tr.grand-total-row')
  await expect(grandRow).toContainText('业务线级合同（未落具体项目）')
  await expect(grandRow).toContainText('已交付 0.47 万')

  // delivery_date 口径：图例 + 列头提示
  await expect(panel.locator('.matrix-legend')).toContainText('按合同交付日期（delivery_date）')
  const groupHead = table.locator('thead .group-head').first()
  await expect(groupHead).toContainText('按交付日期')
  await expect(groupHead).toHaveAttribute('title', /delivery_date/)

  // includeEstimate 联动不回归：切实际口径仍显示线级合同（不消失）
  await panel.getByRole('button', { name: '只看实际' }).click()
  await expect(overviewCell(panel, 5)).toContainText('90') // 实际口径利润（回归锚点）
  await expect(table.locator('tbody tr.line-total-row').first()).toContainText('业务线级合同（未落具体项目）')
})

test('待映射合同可归属业务线级（不落具体项目），POST 携带 businessLineId', async ({ page }) => {
  const fixture: PendingContract = { id: 31, brand: '北汽福田', contractNo: 'FT-2026-002', contractName: '北汽福田定制开发服务合同', bizLineId: 1, receivableAmount: 4650, pending: 1 }
  let currentPending = [fixture]
  await page.unroute('**/api/revenue/contracts/pending**')
  await page.route('**/api/revenue/contracts/pending**', async route => { if (route.request().method() === 'POST') { currentPending = []; return fulfill(route, null) } return fulfill(route, currentPending) })
  await page.goto('/revenue')
  await page.locator('#tab-pending').evaluate(element => (element as HTMLElement).click())
  const panel = page.getByRole('tabpanel', { name: '待映射与销售项目' })
  const pendingTable = panel.locator('.pending-section').filter({ hasText: '合同待映射' }).locator('.data-table')
  const row = pendingTable.locator('tr', { hasText: 'FT-2026-002' })
  await row.locator('.el-select__wrapper').nth(1).click()
  await page.getByRole('option', { name: /业务线级.*不落具体项目/ }).click()
  const resolveRequest = page.waitForRequest(request => /\/api\/revenue\/contracts\/pending\/31\/resolve$/.test(request.url()) && request.method() === 'POST')
  await row.getByRole('button', { name: '确定' }).click()
  expect((await resolveRequest).postDataJSON()).toEqual({ businessLineId: 1 })
  await expect(page.locator('.el-message').last()).toContainText('合同已映射')
  await expect(pendingTable).toContainText('暂无待映射合同')
})

test('已映射黄天鹅合同可从定制切换到 SAAS 项目', async ({ page }) => {
  const original = { id: 41, brand: '黄天鹅', contractNo: 'HT-41', contractName: '黄天鹅合同', bizLineId: 1, projectId: 101, businessLineName: '全渠道云鹿定制', projectName: '皇家项目', receivableAmount: 180800, pending: 0 }
  let currentMapped = [original]
  let mappingBody: unknown
  await page.unroute('**/api/revenue/contracts/pending**')
  await page.route('**/api/revenue/contracts/pending**', route => fulfill(route, []))
  await page.unroute('**/api/revenue/contracts/mapped**')
  await page.route('**/api/revenue/contracts/mapped**', route => fulfill(route, currentMapped))
  await page.route('**/api/revenue/contracts/41/mapping', async route => {
    expect(route.request().method()).toBe('PUT')
    mappingBody = route.request().postDataJSON()
    currentMapped = [{ ...original, bizLineId: 2, projectId: 15, businessLineName: 'SAAS', projectName: '黄天鹅' }]
    return fulfill(route, currentMapped[0])
  })
  await page.route('**/api/business-lines**', route => fulfill(route, { records: [{ id: 1, name: '全渠道云鹿定制' }, { id: 2, name: 'SAAS' }] }))
  await page.route('**/api/projects**', route => fulfill(route, { records: [{ id: 101, name: '皇家项目', businessLineId: 1 }, { id: 15, name: '黄天鹅', businessLineId: 2 }] }))
  await page.goto('/revenue')
  await page.locator('#tab-pending').evaluate(element => (element as HTMLElement).click())
  const panel = page.getByRole('tabpanel', { name: '待映射与销售项目' })
  const mappedSection = panel.locator('.pending-section').filter({ hasText: '已映射合同' })
  const mappedTable = mappedSection.locator('.data-table')
  const row = mappedTable.locator('tr', { hasText: 'HT-41' })
  await row.getByRole('button', { name: '编辑' }).click()
  const lineSelect = row.locator('.el-select').nth(0)
  const projectSelect = row.locator('.el-select').nth(1)
  await lineSelect.locator('.el-select__wrapper').click()
  await lineSelect.locator('input').press('End')
  await lineSelect.locator('input').press('Enter')
  await expect(projectSelect.locator('.el-select__wrapper')).toContainText('业务线级')
  await projectSelect.locator('.el-select__wrapper').click()
  const options = page.getByRole('option')
  await expect(options.filter({ hasText: '黄天鹅' })).toBeVisible()
  await expect(options.filter({ hasText: '皇家项目' })).toHaveCount(0)
  await options.filter({ hasText: '黄天鹅' }).click({ force: true })
  const putRequest = page.waitForRequest(request => request.url().endsWith('/api/revenue/contracts/41/mapping') && request.method() === 'PUT')
  await row.getByRole('button', { name: '保存' }).click()
  const request = await putRequest
  expect(request.method()).toBe('PUT')
  expect(request.postDataJSON()).toEqual({ businessLineId: 2, projectId: 15 })
  await expect.poll(() => mappingBody).toEqual({ businessLineId: 2, projectId: 15 })
  await expect(page.locator('.el-message').last()).toContainText('合同归属已保存')
  await expect(mappedTable).toContainText('SAAS')
  await expect(mappedTable).toContainText('黄天鹅')
})

test('合同工具只出现在导入和待映射 tab', async ({ page }) => {
  await page.goto('/revenue')
  const importTab = page.getByRole('tab', { name: '数据导入' })
  await importTab.click()
  const importPanel = page.getByRole('tabpanel', { name: '数据导入' })
  await expect(importPanel.getByRole('heading', { name: '合同导入', exact: true })).toHaveCount(1)
  await expect(importPanel.getByText('合同导入历史', { exact: false })).toHaveCount(1)
  await page.locator('#tab-pending').evaluate(element => (element as HTMLElement).click())
  const pendingPanel = page.getByRole('tabpanel', { name: '待映射与销售项目' })
  await expect(pendingPanel.getByRole('heading', { name: '合同待映射', exact: false })).toHaveCount(1)
  await expect(pendingPanel.getByRole('heading', { name: '已映射合同（可调整归属）', exact: false })).toHaveCount(1)
  await page.getByRole('tab', { name: '交付与利润' }).click()
  const deliveryPanel = page.getByRole('tabpanel', { name: '交付与利润' })
  await expect(deliveryPanel.getByText('合同导入', { exact: false })).toHaveCount(0)
  await expect(deliveryPanel.getByText('合同待映射', { exact: false })).toHaveCount(0)
  await expect(deliveryPanel.getByText('已映射合同', { exact: false })).toHaveCount(0)
})
