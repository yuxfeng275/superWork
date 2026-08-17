import { expect, test } from '@playwright/test'

const wrap = (data: unknown) => JSON.stringify({
  code: 200,
  message: 'success',
  data,
  timestamp: new Date().toISOString()
})

const listRoutes = [
  '/requirements',
  '/key-matters',
  '/projects',
  '/customers',
  '/opportunities',
  '/statistics',
  '/system/users',
  '/system/roles',
  '/system/menus'
]

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify({
      id: 999,
      username: 'admin',
      realName: '系统管理员',
      role: 'DIRECTOR'
    }))
  })

  await page.route('**/api/**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: wrap([])
  }))
})

test('主布局在平板宽度保持紧凑侧栏和可收缩内容区', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/customers')

  for (const { width, sidebarWidth } of [
    { width: 320, sidebarWidth: 72 },
    { width: 390, sidebarWidth: 72 },
    { width: 720, sidebarWidth: 72 },
    { width: 721, sidebarWidth: 72 },
    { width: 768, sidebarWidth: 72 },
    { width: 1024, sidebarWidth: 72 },
    { width: 1280, sidebarWidth: 260 }
  ]) {
    await page.setViewportSize({ width, height: 844 })
    const metrics = await page.locator('.layout').evaluate(element => {
      const sidebar = element.querySelector<HTMLElement>('.sidebar')!
      const main = element.querySelector<HTMLElement>('.main')!
      const content = element.querySelector<HTMLElement>('.content')!
      const mainRect = main.getBoundingClientRect()

      return {
        sidebarWidth: Math.round(sidebar.getBoundingClientRect().width),
        mainRight: Math.round(mainRect.right),
        mainMinWidth: getComputedStyle(main).minWidth,
        contentMinWidth: getComputedStyle(content).minWidth
      }
    })

    expect(metrics.sidebarWidth).toBe(sidebarWidth)
    expect(metrics.mainRight).toBe(width)
    expect(metrics.mainMinWidth).toBe('0px')
    expect(metrics.contentMinWidth).toBe('0px')
  }
})

test('列表页面在移动端不把宽内容泄漏到主滚动容器', async ({ page }) => {
  for (const width of [320, 390]) {
    await page.setViewportSize({ width, height: 844 })

    for (const path of listRoutes) {
      await page.goto(path)
      await expect(page.locator('.content')).toBeVisible()

      const metrics = await page.locator('.content').evaluate(element => ({
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        documentOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth
      }))

      expect(metrics.scrollWidth, `${path} should keep horizontal overflow local at ${width}px`).toBeLessThanOrEqual(metrics.clientWidth + 1)
      expect(metrics.documentOverflow, `${path} should stay inside the ${width}px viewport`).toBe(false)
    }
  }
})

test('需求和大事儿宽表保留局部横向滚动', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })

  await page.goto('/requirements')
  const requirementTable = await page.locator('.table-card').evaluate(element => ({
    locallyScrollable: element.scrollWidth > element.clientWidth,
    contentOverflow: document.querySelector<HTMLElement>('.content')!.scrollWidth > document.querySelector<HTMLElement>('.content')!.clientWidth
  }))
  expect(requirementTable).toEqual({ locallyScrollable: true, contentOverflow: false })

  await page.goto('/key-matters')
  const matterTable = await page.getByRole('region', { name: '大事儿列表', exact: true }).evaluate(panel => {
    const table = panel.querySelector<HTMLElement>('.matter-table')!
    const scroller = table.querySelector<HTMLElement>('.el-scrollbar__wrap')!
    const operationHeader = Array.from(table.querySelectorAll<HTMLElement>('th'))
      .find(element => element.textContent?.trim() === '操作')!
    const panelRect = panel.getBoundingClientRect()
    const tableRect = table.getBoundingClientRect()
    const operationRect = operationHeader.getBoundingClientRect()

    return {
      tableFitsPanel: tableRect.width <= panelRect.width + 1,
      scrollerScrollable: scroller.scrollWidth > scroller.clientWidth,
      operationInsidePanel: operationRect.left >= panelRect.left - 1 && operationRect.right <= panelRect.right + 1,
      contentOverflow: document.querySelector<HTMLElement>('.content')!.scrollWidth > document.querySelector<HTMLElement>('.content')!.clientWidth
    }
  })

  expect(matterTable).toEqual({
    tableFitsPanel: true,
    scrollerScrollable: true,
    operationInsidePanel: true,
    contentOverflow: false
  })
})

test('项目和用户卡片在 320px 下收缩到各自网格内', async ({ page }) => {
  const businessLines = [{ id: 1, name: '全渠道云鹿定制', status: 1 }]
  const projects = [{
    id: 1,
    businessLineId: 1,
    parentId: null,
    level: 1,
    name: '皇家项目长名称用于小屏卡片边界检查',
    fullPath: '皇家项目长名称用于小屏卡片边界检查',
    code: 'ROYAL-LONG-CODE',
    managerId: 1,
    status: 1,
    children: []
  }]
  const users = [{
    id: 1,
    username: 'responsive_user_with_long_name',
    realName: '响应式测试用户',
    role: 'FULL_STACK_ENGINEER',
    status: 1
  }]

  await page.route('**/api/business-lines**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: wrap({ records: businessLines })
  }))
  await page.route('**/api/projects**', route => {
    const path = new URL(route.request().url()).pathname
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: wrap(path === '/api/projects/tree' ? projects : { records: projects })
    })
  })
  await page.route('**/api/users**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: wrap({ records: users })
  }))

  await page.setViewportSize({ width: 320, height: 844 })
  await page.goto('/projects')
  await expect(page.locator('.project-card')).toHaveCount(1)
  const projectCardFits = await page.locator('.project-card').evaluate(card => {
    const grid = card.parentElement!
    const cardRect = card.getBoundingClientRect()
    const gridRect = grid.getBoundingClientRect()
    return cardRect.left >= gridRect.left && cardRect.right <= gridRect.right
  })
  expect(projectCardFits).toBe(true)

  await page.goto('/system/users')
  await expect(page.locator('[data-testid="user-card"]')).toHaveCount(1)
  const userCardFits = await page.locator('[data-testid="user-card"]').evaluate(card => {
    const grid = card.parentElement!
    const cardRect = card.getBoundingClientRect()
    const gridRect = grid.getBoundingClientRect()
    return cardRect.left >= gridRect.left && cardRect.right <= gridRect.right
  })
  expect(userCardFits).toBe(true)
  expect(await page.locator('.content').evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
})
