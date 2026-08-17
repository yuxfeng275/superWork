import { expect, test } from '@playwright/test'

const wrap = (data: unknown) => JSON.stringify({
  code: 200,
  message: 'success',
  data,
  timestamp: new Date().toISOString()
})

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify({ id: 999, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' }))
  })

  await page.route('**/api/**', route => route.fulfill({ status: 200, contentType: 'application/json', body: wrap([]) }))
  await page.route('**/api/business-lines**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: wrap({ records: [{ name: '全渠道云鹿定制超长业务线名称' }, { name: '解决方案与数据智能业务线' }] })
  }))
  await page.route('**/api/customer-contacts**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: wrap({ records: [{ company: '皇家集团' }] })
  }))
})

test('商机管理支持业务线标签、详情、跟进、工时和看板拖拽', async ({ page }) => {
  const updates: any[] = []
  const followUps: any[] = []
  const worklogs: any[] = []
  const rows = [
    { id: 1, name: '皇家项目-年度续约长名称用于检查列表不换行', customer: '皇家集团', type: '商机', status: '需求确认', amount: 360, owner: '', businessLine: '全渠道云鹿定制超长业务线名称', nextFollowUp: '今天 14:00', createdAt: '2026-08-06T09:08:07', probability: 30, note: '客户关注报价' },
    { id: 2, name: '飞鹤会员运营', customer: '飞鹤乳业', type: '线索', status: '初步接触', amount: 120, owner: '姜涛', businessLine: '解决方案与数据智能业务线', nextFollowUp: '明天', createdAt: '2026-08-05', probability: 20 }
  ]

  await page.route('**/api/sales-opportunities**', route => route.fulfill({ status: 200, contentType: 'application/json', body: wrap(rows) }))
  await page.route('**/api/sales-opportunities/*', route => {
    if (route.request().method() === 'PUT') updates.push(route.request().postDataJSON())
    return route.fulfill({ status: 200, contentType: 'application/json', body: wrap({ id: 1, ...(route.request().postDataJSON() || {}) }) })
  })
  await page.route('**/api/sales-opportunities/*/support-worklogs', route => {
    if (route.request().method() === 'POST') {
      worklogs.push(route.request().postDataJSON())
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: wrap(route.request().method() === 'POST'
        ? { id: 9, ...route.request().postDataJSON() }
        : [{ id: 6, opportunityId: 1, supportDate: '2026-08-06', supporter: '售前A', hours: 2, supportType: '方案支持', content: '输出方案初稿' }])
    })
  })
  await page.route('**/api/sales-opportunities/*/follow-ups', route => {
    if (route.request().method() === 'POST') followUps.push(route.request().postDataJSON())
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: wrap(route.request().method() === 'POST'
        ? { id: 10, opportunityId: 1, ...route.request().postDataJSON() }
        : [])
    })
  })

  await page.goto('/opportunities')
  await expect(page.getByRole('button', { name: '全渠道云鹿定制超长业务线名称' }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '导入' })).toHaveCount(0)
  await expect(page.locator('.opportunity-name strong').first()).toHaveCSS('white-space', 'nowrap')
  await expect(page.locator('.opportunity-table th', { hasText: '下次跟进' })).toHaveCount(0)
  await expect(page.getByLabel('商机列表').getByText('2026-08-06 09:08')).toBeVisible()

  await page.getByRole('button', { name: '详情' }).first().click()
  const detailDialog = page.locator('.el-dialog').filter({ hasText: '商机详情' })
  await expect(detailDialog).toContainText('售前A')
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(detailDialog).toBeHidden()

  await page.locator('.opportunity-table-panel').getByRole('button', { name: '跟进' }).first().click()
  const followDialog = page.getByRole('dialog', { name: '商机跟进记录' })
  await followDialog.getByLabel('跟进情况').fill('客户确认采购窗口')
  await followDialog.getByPlaceholder('如：明天 10:00 / 下周三 / 已成交').fill('周五 15:00')
  await followDialog.getByRole('button', { name: '添加跟进记录' }).click()
  await expect.poll(() => followUps.length).toBe(1)
  await followDialog.getByRole('button', { name: '关闭', exact: true }).click()

  await page.locator('.opportunity-table-panel').getByRole('button', { name: '工时' }).first().click()
  await page.getByPlaceholder('请输入支持人员').fill('售前B')
  await page.getByPlaceholder('填写售前支持内容和产出').fill('客户演示和报价支持')
  await page.getByRole('button', { name: '登记工时' }).click()
  await expect.poll(() => worklogs.length).toBe(1)
  expect(worklogs[0].supporter).toBe('售前B')
  const worklogDialog = page.locator('.el-dialog').filter({ hasText: '售前支持工时登记' })
  await worklogDialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(worklogDialog).toBeHidden()

  const updatesBeforeDrag = updates.length
  await page.getByText('看板视图').click()
  await expect(page.locator('.opportunity-board')).toBeVisible()
  const dataTransfer = await page.evaluateHandle(() => new DataTransfer())
  const card = page.locator('.board-card').filter({ hasText: '皇家项目' }).first()
  const targetColumn = page.locator('.board-column').filter({ hasText: '商务谈判' })
  await card.dispatchEvent('dragstart', { dataTransfer })
  await targetColumn.dispatchEvent('dragenter', { dataTransfer })
  await targetColumn.dispatchEvent('dragover', { dataTransfer })
  await targetColumn.dispatchEvent('drop', { dataTransfer })
  await expect.poll(() => updates.length).toBeGreaterThan(updatesBeforeDrag)
  expect(updates.at(-1).status).toBe('商务谈判')
})

test('商机跟进追加历史记录并保留每次跟进情况', async ({ page }) => {
  const createdFollowUps: any[] = []
  const opportunityUpdates: any[] = []
  const row = {
    id: 1,
    name: '皇家项目年度续约',
    customer: '皇家集团',
    type: '商机',
    status: '需求确认',
    amount: 360,
    owner: '姜涛',
    businessLine: '全渠道云',
    nextFollowUp: '周三',
    createdAt: '2026-08-06',
    probability: 30,
    note: '商机长期备注'
  }
  const history = [{
    id: 8,
    opportunityId: 1,
    followUpAt: '2026-08-08T10:00:00',
    follower: '姜涛',
    content: '首次沟通，客户确认续约范围',
    status: '需求确认',
    probability: 30,
    nextFollowUp: '周三',
    createdAt: '2026-08-08T10:01:00'
  }]

  await page.route('**/api/sales-opportunities**', route => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/sales-opportunities/1/follow-ups') {
      if (request.method() === 'POST') {
        const payload = request.postDataJSON()
        createdFollowUps.push(payload)
        history.unshift({ id: 9, opportunityId: 1, createdAt: '2026-08-10T14:31:00', ...payload })
        return route.fulfill({ status: 200, contentType: 'application/json', body: wrap(history[0]) })
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: wrap(history) })
    }
    if (pathname === '/api/sales-opportunities/1/support-worklogs') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: wrap([]) })
    }
    if (pathname === '/api/sales-opportunities/1' && request.method() === 'PUT') {
      opportunityUpdates.push(request.postDataJSON())
      return route.fulfill({ status: 200, contentType: 'application/json', body: wrap(row) })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: wrap([row]) })
  })

  await page.goto('/opportunities')
  await page.locator('.opportunity-table-panel').getByRole('button', { name: '跟进' }).click()
  const dialog = page.getByRole('dialog', { name: '商机跟进记录' })
  await expect(dialog.getByText('首次沟通，客户确认续约范围')).toBeVisible()
  await expect(dialog.getByLabel('跟进人')).toHaveValue('系统管理员')
  await dialog.getByLabel('跟进情况').fill('客户认可一期范围，等待采购确认预算')
  await dialog.getByPlaceholder('如：明天 10:00 / 下周三 / 已成交').fill('周五 15:00')
  await dialog.getByRole('button', { name: '添加跟进记录' }).click()

  await expect.poll(() => createdFollowUps.length).toBe(1)
  expect(createdFollowUps[0]).toMatchObject({
    follower: '系统管理员',
    content: '客户认可一期范围，等待采购确认预算',
    status: '需求确认',
    probability: 30,
    nextFollowUp: '周五 15:00'
  })
  expect(opportunityUpdates).toHaveLength(0)
  await expect(dialog.getByText('客户认可一期范围，等待采购确认预算')).toBeVisible()
  await expect(dialog.getByText('首次沟通，客户确认续约范围')).toBeVisible()
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()

  await page.locator('.opportunity-table-panel').getByRole('button', { name: '详情' }).click()
  const detailHistory = page.getByLabel('商机跟进历史')
  await expect(detailHistory.getByText('客户认可一期范围，等待采购确认预算')).toBeVisible()
  await expect(detailHistory.getByText('首次沟通，客户确认续约范围')).toBeVisible()
})

test('商机列表在中小屏使用表格内部滚动且操作列保持可用', async ({ page }) => {
  await page.route('**/api/sales-opportunities**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: wrap([
      { id: 1, name: '皇家项目-年度续约长名称用于响应式检查', customer: '皇家集团', type: '商机', status: '需求确认', amount: 360, owner: '姜涛', businessLine: '全渠道云鹿定制超长业务线名称', nextFollowUp: '今天 14:00', createdAt: '2026-08-06', probability: 30 }
    ])
  }))

  await page.setViewportSize({ width: 1024, height: 768 })
  await page.goto('/opportunities')
  await expect(page.locator('.opportunity-table .el-table__body-wrapper tbody tr')).toHaveCount(1)

  const readTableMetrics = () => page.evaluate(() => {
    const panel = document.querySelector<HTMLElement>('.opportunity-table-panel')!
    const table = document.querySelector<HTMLElement>('.opportunity-table')!
    const scroller = table.querySelector<HTMLElement>('.el-scrollbar__wrap')!
    const operationHeader = Array.from(table.querySelectorAll<HTMLElement>('th'))
      .find(element => element.textContent?.trim() === '操作')!
    const panelRect = panel.getBoundingClientRect()
    const tableRect = table.getBoundingClientRect()
    const scrollerRect = scroller.getBoundingClientRect()
    const operationRect = operationHeader.getBoundingClientRect()

    return {
      documentOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      panelInsideViewport: panelRect.left >= 0 && panelRect.right <= window.innerWidth,
      tableFitsPanel: tableRect.width <= panelRect.width + 1,
      scrollerFitsPanel: scrollerRect.width <= panelRect.width + 1,
      scrollerScrollable: scroller.scrollWidth > scroller.clientWidth,
      operationInsidePanel: operationRect.left >= panelRect.left - 1 && operationRect.right <= panelRect.right + 1,
      operationWidth: operationRect.width
    }
  })

  expect(await readTableMetrics()).toMatchObject({
    documentOverflow: false,
    panelInsideViewport: true,
    tableFitsPanel: true,
    scrollerFitsPanel: true,
    scrollerScrollable: true,
    operationInsidePanel: true
  })

  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(async () => (await readTableMetrics()).operationWidth).toBeLessThanOrEqual(105)
  const mobileMetrics = await readTableMetrics()
  expect(mobileMetrics).toMatchObject({
    documentOverflow: false,
    panelInsideViewport: true,
    tableFitsPanel: true,
    scrollerFitsPanel: true,
    scrollerScrollable: true,
    operationInsidePanel: true
  })

  const scrollLeft = await page.locator('.opportunity-table .el-scrollbar__wrap').evaluate(element => {
    element.scrollLeft = element.scrollWidth
    return element.scrollLeft
  })
  expect(scrollLeft).toBeGreaterThan(0)
  await expect(page.locator('.opportunity-table-panel').getByRole('button', { name: '更多操作' }).first()).toBeVisible()
})
