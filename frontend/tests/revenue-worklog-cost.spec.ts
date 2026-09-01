import { expect, test } from '@playwright/test'
import type { Route } from '@playwright/test'

const emptyCell = { hours: 0, cost: 0, source: null }
const cells = (overrides: Record<number, object>) =>
  Array.from({ length: 12 }, (_, i) => ({ ...emptyCell, ...(overrides[i] || {}) }))

const matrix = {
  year: 2026,
  months: Array.from({ length: 12 }, (_, i) => ({
    yearMonth: `2026-${String(i + 1).padStart(2, '0')}`,
    closed: i < 7   // 1-7月已完结
  })),
  lines: [
    {
      businessLineId: 1,
      businessLineName: '全渠道云鹿定制',
      mode: 'full',
      sections: [
        {
          type: 'project',
          rows: [
            {
              rowKey: 'p-11', name: '皇家项目', kind: 'project', projectId: 11, unitPrice: 22790.7,
              months: cells({ 6: { hours: 4.3, cost: 98000, source: 'actual' }, 8: { hours: 1.5, cost: 30000, source: 'estimate', estimateCount: 1 } }),
              totals: { hours: 5.8, cost: 128000, source: 'mixed' }
            }
          ]
        },
        {
          type: 'sales',
          rows: [
            {
              rowKey: 'sp-9', name: '京博', kind: 'sales_specific', salesProjectId: 9,
              opportunityId: 31, opportunityName: '京博智慧园区',
              months: cells({ 6: { hours: 0.2, cost: 3069, source: 'actual' } }),
              totals: { hours: 0.2, cost: 3069, source: 'actual' }
            },
            {
              rowKey: 'pool-1', name: '商机集合', kind: 'pool',
              months: cells({ 6: { hours: 0.8, cost: 22164, source: 'actual' } }),
              totals: { hours: 0.8, cost: 22164, source: 'actual' }
            },
            {
              rowKey: 'other-1', name: '其他', kind: 'other',
              months: cells({}), totals: { ...emptyCell }
            }
          ]
        }
      ],
      monthTotals: cells({ 6: { hours: 5.3, cost: 123233, source: 'actual' }, 8: { hours: 1.5, cost: 30000, source: 'estimate' } }),
      totals: { hours: 6.8, cost: 153233, source: 'mixed' }
    },
    {
      businessLineId: 3,
      businessLineName: '会员通',
      mode: 'aggregate',
      sections: [
        {
          type: 'project',
          rows: [{
            rowKey: 'agg-project-3', name: '项目', kind: 'agg_project',
            months: cells({ 6: { hours: 1.6, cost: 33695, source: 'actual' } }),
            totals: { hours: 1.6, cost: 33695, source: 'actual' }
          }]
        },
        {
          type: 'sales',
          rows: [{
            rowKey: 'agg-sales-3', name: '销售', kind: 'agg_sales',
            months: cells({ 6: { hours: 0.7, cost: 14680, source: 'actual' } }),
            totals: { hours: 0.7, cost: 14680, source: 'actual' }
          }]
        }
      ],
      monthTotals: cells({ 6: { hours: 2.3, cost: 48375, source: 'actual' } }),
      totals: { hours: 2.3, cost: 48375, source: 'actual' }
    },
    {
      businessLineId: 5,
      businessLineName: '全渠道产品',
      mode: 'simple',
      sections: [
        {
          type: 'project',
          rows: [{
            rowKey: 'simple-5', name: '全渠道产品', kind: 'simple',
            months: cells({ 6: { hours: 0.4, cost: 8000, source: 'actual' } }),
            totals: { hours: 0.4, cost: 8000, source: 'actual' }
          }]
        },
        { type: 'sales', rows: [] }
      ],
      monthTotals: cells({ 6: { hours: 0.4, cost: 8000, source: 'actual' } }),
      totals: { hours: 0.4, cost: 8000, source: 'actual' }
    }
  ],
  monthTotals: cells({ 6: { hours: 5.3, cost: 123233, source: 'actual' }, 8: { hours: 1.5, cost: 30000, source: 'estimate' } }),
  grandTotal: { hours: 6.8, cost: 153233, source: 'mixed' },
  overview: {
    totalHours: 6.8, projectHours: 5.8, salesHours: 1.0, totalCost: 153233,
    avgUnitPrice: 22534.26, closedMonthCount: 7
  }
}

const fulfill = (route: Route, data: unknown) => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, data })
})

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify({ id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' }))
  })
  await page.route('**/api/revenue/matrix**', route => fulfill(route, matrix))
  await page.route('**/api/revenue/cell-detail**', route => {
    const url = new URL(route.request().url())
    const yearMonth = url.searchParams.get('yearMonth')
    if (yearMonth !== '2026-07') {
      return fulfill(route, {
        closed: false,
        estimates: yearMonth === '2026-09' ? [{
          id: 51, yearMonth: '2026-09', businessLineId: 1, projectId: 11, workType: 'project',
          description: '黄天鹅物码项目支持', personMonths: 1.5, unitPrice: 22790.7, amount: 34186.05
        }] : []
      })
    }
    return fulfill(route, {
      closed: true,
      worklogEntries: [
        { id: 1, yearMonth: '2026-07', employeeName: '翁擎天', department: '全域业务拓展部', hours: 0.15, workNote: '佳贝日常问题', tags: '澳优,佳贝艾特' }
      ],
      costEntries: [
        { id: 2, yearMonth: '2026-07', projectNameRaw: '皇家全渠道项目【交付】', employeeCount: 14, hours: 4.3, costAmount: 98000, personMonthCost: 22790.7 }
      ],
      estimates: [
        { id: 50, yearMonth: '2026-07', businessLineId: 1, projectId: 11, workType: 'project', description: '7月预估', personMonths: 4, unitPrice: 22790.7, amount: 91162.8 }
      ]
    })
  })
  await page.route('**/api/revenue/estimates**', route => fulfill(route, []))
  await page.route('**/api/revenue/imports**', route => fulfill(route, []))
  await page.route('**/api/revenue/pending', route => fulfill(route, { worklog: [], cost: [] }))
  await page.route('**/api/revenue/sales-projects', route => fulfill(route, [
    { id: 9, businessLineId: 1, name: '京博', opportunityId: 31, opportunityName: '京博智慧园区' }
  ]))
  await page.route('**/api/revenue/opportunity-options', route => fulfill(route, [
    { id: 31, name: '京博智慧园区', customer: '京博' }
  ]))
  await page.route('**/api/business-lines**', route => fulfill(route, { records: [{ id: 1, name: '全渠道云鹿定制' }], total: 1 }))
  await page.route('**/api/projects**', route => fulfill(route, { records: [{ id: 11, name: '皇家项目', businessLineId: 1 }], total: 1 }))
})

test('营收矩阵展示完结实际值与未完结预估', async ({ page }, testInfo) => {
  await page.goto('/revenue')
  const table = page.locator('.matrix-table')
  await expect(table).toBeVisible()

  // 行结构：项目行 + 销售行（项目集已移除）
  await expect(table).toContainText('皇家项目')
  await expect(table).toContainText('京博')
  await expect(table).toContainText('商机:京博智慧园区')
  await expect(table).toContainText('商机集合')
  await expect(table).toContainText('其他')

  // 完结月（7月）实际值：成本 9.8 万 + 工时 4.3 人月
  const royalRow = table.locator('tr', { hasText: '皇家项目' })
  await expect(royalRow.locator('td.actual').first()).toContainText('9.8')
  await expect(royalRow.locator('td.actual').first()).toContainText('4.3')

  // 未完结月（9月）预估标记
  await expect(royalRow.locator('td.estimate').first()).toContainText('预')
  await expect(royalRow.locator('td.estimate').first()).toContainText('1.5')

  // 完结月表头带「完」标记，合计行存在
  await expect(table.locator('.month-head.closed')).toHaveCount(7)
  await expect(table.locator('.grand-total-row')).toContainText('20.96')

  // 概览卡
  await expect(page.locator('.overview-strip')).toContainText('年度总工时')
  await expect(page.locator('.overview-strip')).toContainText('9.5')
  await expect(page.locator('.overview-strip')).toContainText('2.21')   // 综合单价 万/人月

  if (process.env.CAPTURE_REVENUE === '1') {
    await page.screenshot({ path: testInfo.outputPath('revenue-matrix.png'), fullPage: true })
  }
})

test('切换仅工时后单元格不再显示成本', async ({ page }) => {
  await page.goto('/revenue')
  const royalRow = page.locator('.matrix-table tr', { hasText: '皇家项目' })
  await expect(royalRow.locator('td.actual').first()).toContainText('9.8')

  await page.locator('.segment-switch[aria-label="展示内容"] button', { hasText: '仅工时' }).click()
  await expect(royalRow.locator('td.actual').first()).not.toContainText('9.8')
  await expect(royalRow.locator('td.actual').first()).toContainText('4.3')

  await page.locator('.segment-switch[aria-label="展示内容"] button', { hasText: '仅成本' }).click()
  await expect(royalRow.locator('td.actual').first()).toContainText('9.8')
  await expect(royalRow.locator('td.actual').first()).not.toContainText('4.3')
})

test('只看实际口径隐藏预估且合计同步剔除', async ({ page }) => {
  await page.goto('/revenue')
  const table = page.locator('.matrix-table')
  const royalRow = table.locator('tr', { hasText: '皇家项目' })

  // 含预估：9 月预估格可见，皇家合计含预估 5.8 人月，总成本含预估 3 万
  await expect(royalRow.locator('td.estimate').first()).toContainText('预')
  await expect(royalRow.locator('td.col-total')).toContainText('5.8')
  await expect(table.locator('.grand-total-row')).toContainText('20.96')

  await page.locator('.segment-switch[aria-label="数据口径"] button', { hasText: '只看实际' }).click()
  await expect(royalRow.locator('td.estimate')).toHaveCount(0)
  await expect(royalRow.locator('td.col-total')).toContainText('4.3')
  await expect(royalRow.locator('td.col-total')).not.toContainText('5.8')
  await expect(table.locator('.grand-total-row')).toContainText('17.96')
  await expect(table.locator('.grand-total-row')).not.toContainText('20.96')
  await expect(page.locator('.overview-strip')).toContainText('8')   // 总工时 9.5 减预估 1.5

  await page.locator('.segment-switch[aria-label="数据口径"] button', { hasText: '含预估' }).click()
  await expect(royalRow.locator('td.estimate').first()).toContainText('预')
})

test('完结月单元格下钻展示工时与成本明细及预估偏差', async ({ page }) => {
  await page.goto('/revenue')
  const royalRow = page.locator('.matrix-table tr', { hasText: '皇家项目' })
  await royalRow.locator('td.actual').first().click()

  const drawer = page.getByRole('dialog')
  // 预估 vs 实际偏差块：预估 4 人月 / 实际 0.15+4.3 取工时明细 0.15
  await expect(drawer.locator('.deviation-block')).toBeVisible()
  await expect(drawer.locator('.deviation-block')).toContainText('预估 4')
  await expect(drawer.locator('.deviation-block')).toContainText('实际 0.15')
  await expect(drawer.locator('.deviation-block')).toContainText('-96.3%')
  await expect(drawer).toContainText('工时明细')
  await expect(drawer).toContainText('翁擎天')
  await expect(drawer).toContainText('佳贝艾特')   // 标签
  await expect(drawer).toContainText('成本明细')
  await expect(drawer).toContainText('98000')
})

test('未完结月单元格展示预估明细并可新增', async ({ page }) => {
  await page.goto('/revenue')
  const royalRow = page.locator('.matrix-table tr', { hasText: '皇家项目' })
  await royalRow.locator('td.estimate').first().click()

  const drawer = page.getByRole('dialog')
  await expect(drawer).toContainText('预估明细')
  await expect(drawer).toContainText('黄天鹅物码项目支持')
  await expect(drawer).toContainText('2.28')   // 历史完结单价（万/人月）

  await drawer.getByRole('button', { name: '新增预估' }).click()
  const dialog = page.getByRole('dialog', { name: '新增预估' })
  // 按月批量：9 月默认 1 人月；10 月填 2 人月并单独写事项
  const grid = dialog.getByLabel('按月批量预估')
  await grid.getByLabel('2026-10人月').fill('2')
  await grid.getByLabel('2026-10事项说明').fill('10月定制支持')
  await dialog.getByPlaceholder('例如：黄天鹅物码项目支持').fill('皇家二期联调支持')

  const posted: { yearMonth: string; personMonths: number; description: string }[] = []
  await page.route('**/api/revenue/estimates', route => {
    if (route.request().method() === 'POST') {
      posted.push(route.request().postDataJSON())
      return fulfill(route, { id: 52 })
    }
    return fulfill(route, [])
  })
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.el-message')).toContainText('已为 2 个月份创建预估')
  expect(posted).toHaveLength(2)
  expect(posted.map(item => item.yearMonth).sort()).toEqual(['2026-09', '2026-10'])
  const october = posted.find(item => item.yearMonth === '2026-10')
  expect(october?.personMonths).toBe(2)
  expect(october?.description).toBe('10月定制支持')
  const september = posted.find(item => item.yearMonth === '2026-09')
  expect(september?.description).toBe('皇家二期联调支持')
})

test('会员通聚合为项目销售两行且简单业务线单行不可下钻', async ({ page }) => {
  await page.goto('/revenue')
  const table = page.locator('.matrix-table')

  // 项目集行全局移除
  await expect(table.locator('td.col-project').getByText('项目集', { exact: true })).toHaveCount(0)

  // 会员通：项目/销售两个聚合行，数值正确
  const memberProjectRow = table.locator('tr', {
    has: page.locator('td.col-project').getByText('项目', { exact: true })
  })
  await expect(memberProjectRow).toContainText('3.37')
  await expect(memberProjectRow).toContainText('1.6')
  const memberSalesRow = table.locator('tr', {
    has: page.locator('td.col-project').getByText('销售', { exact: true })
  })
  await expect(memberSalesRow).toContainText('1.47')
  await expect(memberSalesRow).toContainText('0.7')
  // 商机集合只剩定制线一行（会员通不拆销售细分）
  await expect(table.locator('td.col-project').getByText('商机集合', { exact: true })).toHaveCount(1)

  // 全渠道产品：单行，类型列为 —，完结月实际格不可点击（未完结月可录预估）
  const productRow = table.locator('tbody tr:not(.line-total-row)', { hasText: '全渠道产品' })
  await expect(productRow).toContainText('0.8')
  await expect(productRow.locator('.col-type')).toHaveText('—')
  await expect(productRow.locator('td.actual.clickable')).toHaveCount(0)
})

test('完结月单元格支持补录和修改工时明细', async ({ page }) => {
  await page.goto('/revenue')
  const royalRow = page.locator('.matrix-table tr', { hasText: '皇家项目' })
  await royalRow.locator('td.actual').first().click()

  const drawer = page.getByRole('dialog')
  await expect(drawer.getByRole('button', { name: '新增工时' })).toBeVisible()
  await expect(drawer.getByRole('button', { name: '新增成本' })).toBeVisible()

  // 编辑已有工时明细
  await drawer.getByRole('button', { name: '编辑' }).first().click()
  const editDialog = page.getByRole('dialog', { name: '编辑工时明细' })
  await expect(editDialog).toBeVisible()
  await expect(editDialog.locator('input').first()).toHaveValue('翁擎天')

  const updateRequest = page.waitForRequest(request =>
    request.url().includes('/api/revenue/worklog-entries/') && request.method() === 'PUT')
  await page.route('**/api/revenue/worklog-entries/*', route => fulfill(route, { id: 1 }))
  await editDialog.getByRole('button', { name: '保存' }).click()
  expect((await updateRequest).postDataJSON()).toMatchObject({
    yearMonth: '2026-07',
    businessLineId: 1,
    projectId: 11,
    workType: 'project',
    hours: 0.15
  })
})

test('筛选 pill 支持多选且小计紧随业务线', async ({ page }) => {
  await page.goto('/revenue')
  const table = page.locator('.matrix-table')

  // 多选两个业务线：定制 + 会员通
  await page.locator('.filter-pills[aria-label="业务线筛选"] .filter-pill', { hasText: '全渠道云鹿定制' }).click()
  await page.locator('.filter-pills[aria-label="业务线筛选"] .filter-pill', { hasText: '会员通' }).click()
  await expect(table).toContainText('皇家项目')
  await expect(table).toContainText('会员通')
  await expect(table).not.toContainText('全渠道产品')
  // 合计 = 定制(15.32万) + 会员通(4.84万)
  await expect(table.locator('.grand-total-row')).toContainText('20.16')

  // 小计行紧随其业务线最后一行数据之后
  const lineNames = await table.locator('tbody tr td.col-line').allTextContents()
  const customIdx = lineNames.findIndex(text => text.includes('全渠道云鹿定制'))
  const customSubtotalIdx = lineNames.findIndex((text, i) => i > customIdx && text.includes('全渠道云鹿定制'))
  expect(customSubtotalIdx).toBeGreaterThan(customIdx)
  const subtotalRow = table.locator('tbody tr.line-total-row', { hasText: '全渠道云鹿定制' })
  await expect(subtotalRow.locator('td').first()).toHaveCSS('font-weight', '700')
  // 小计行上一行是同业务线的数据行（不是其他业务线）
  const prevRowText = await subtotalRow.locator('xpath=preceding-sibling::tr[1]').innerText()
  expect(prevRowText).toContain('其他')

  // 重置
  await page.locator('.filter-pill.reset').click()
  await expect(table.locator('.grand-total-row')).toContainText('20.96')

  // 单行汇总业务线（全渠道产品）不渲染小计行，全表只有定制和会员通两个小计
  await expect(table.locator('tbody tr.line-total-row')).toHaveCount(2)
  await expect(table.locator('tbody tr.line-total-row', { hasText: '全渠道产品' })).toHaveCount(0)
})

test('业务线和项目筛选联动合计与概览', async ({ page }) => {
  await page.goto('/revenue')
  const table = page.locator('.matrix-table')
  await expect(table).toContainText('皇家项目')

  // 筛选条在概览卡上方
  const filterRow = page.locator('.filter-row')
  await expect(filterRow).toBeVisible()
  const filterBox = await filterRow.boundingBox()
  const overviewBox = await page.locator('.overview-strip').boundingBox()
  expect(filterBox!.y).toBeLessThan(overviewBox!.y)

  // 无筛选时：合计行显示全量
  await expect(table.locator('.grand-total-row')).toContainText('20.96')

  // pill 筛选项目：只显示皇家项目行，合计随之为皇家的数字（12.8 万 / 5.8 人月）
  await page.locator('.filter-pills[aria-label="项目筛选"] .filter-pill', { hasText: '皇家项目' }).click()
  await expect(table).toContainText('皇家项目')
  await expect(table).not.toContainText('商机集合')
  await expect(table).not.toContainText('全渠道产品')
  await expect(table.locator('.grand-total-row')).toContainText('12.8')
  await expect(page.locator('.overview-strip')).toContainText('5.8')

  // pill 筛选业务线会员通：项目筛选被重置，显示会员通全部行
  await page.locator('.filter-pills[aria-label="业务线筛选"] .filter-pill', { hasText: '会员通' }).click()
  await expect(table).toContainText('会员通')
  await expect(table).not.toContainText('皇家项目')
  await expect(table.locator('.grand-total-row')).toContainText('4.84')

  // 重置恢复全量
  await page.locator('.filter-pill.reset').click()
  await expect(table.locator('.grand-total-row')).toContainText('20.96')
})

test('全渠道产品未完结月可录入预估，完结月仍不可下钻', async ({ page }) => {
  await page.goto('/revenue')
  const productRow = page.locator('.matrix-table tbody tr:not(.line-total-row)', { hasText: '全渠道产品' })

  // 完结月（7月实际格）不可点击
  await expect(productRow.locator('td.actual.clickable')).toHaveCount(0)
  // 未完结月（8月空格）可点击
  const augustCell = productRow.locator('td.col-month').nth(7)
  await expect(augustCell).toHaveClass(/clickable/)
  await augustCell.click()

  const drawer = page.getByRole('dialog')
  await expect(drawer).toContainText('预估明细')

  const posted: { yearMonth: string; businessLineId: number; workType: string }[] = []
  await page.route('**/api/revenue/estimates', route => {
    if (route.request().method() === 'POST') {
      posted.push(route.request().postDataJSON())
      return fulfill(route, { id: 60 })
    }
    return fulfill(route, [])
  })
  await drawer.getByRole('button', { name: '新增预估' }).click()
  const dialog = page.getByRole('dialog', { name: '新增预估' })
  await dialog.getByPlaceholder('例如：黄天鹅物码项目支持').fill('产品迭代投入')
  await dialog.getByRole('button', { name: '保存' }).click()
  expect(posted).toHaveLength(1)
  expect(posted[0]).toMatchObject({ yearMonth: '2026-08', businessLineId: 5, workType: 'project', description: '产品迭代投入', personMonths: 1
  })
})

test('点击未完结月表头可标记完结', async ({ page }) => {
  await page.goto('/revenue')
  let closedMonth = ''
  await page.route('**/api/revenue/months/*/close', route => {
    closedMonth = route.request().url().match(/months\/([\d-]+)\/close/)?.[1] || ''
    return fulfill(route, null)
  })

  await page.locator('.matrix-table .month-head:not(.closed)').first().click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.locator('.el-message')).toContainText('已完结')
  expect(closedMonth).toBe('2026-08')
})
