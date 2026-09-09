import { expect, test } from '@playwright/test'

// 直连 241 生产环境（真实数据、真实接口）验证 H1/H2 切换渲染
test('生产环境 H1 H2 切换实测', async ({ page }) => {
  test.setTimeout(120000)
  await page.goto('/')
  await page.waitForTimeout(1000)
  // 登录
  await page.locator('input[placeholder*="用户名"], input[type="text"]').first().fill('admin')
  await page.locator('input[type="password"]').first().fill('123456')
  await page.getByRole('button', { name: /登录|登 录/ }).click()
  await page.waitForTimeout(1500)
  // 进入营收管理
  await page.getByText('营收管理', { exact: true }).first().click()
  await page.waitForTimeout(2000)
  // 切到交付与利润 tab
  await page.getByRole('tab', { name: '交付与利润' }).click()
  await page.waitForTimeout(2000)
  const tbody = page.locator('.matrix-table tbody').last()
  await expect(tbody).toBeVisible()

  const ytd = await tbody.innerText()
  await page.locator('.delivery-toolbar .segment-switch').first().getByRole('button', { name: '上半年 H1' }).click()
  await page.waitForTimeout(500)
  const h1 = await tbody.innerText()
  await page.locator('.delivery-toolbar .segment-switch').first().getByRole('button', { name: '下半年 H2' }).click()
  await page.waitForTimeout(500)
  const h2 = await tbody.innerText()

  console.log('=== 生产实测：YTD vs H1 vs H2 ===')
  console.log('YTD==H1?', ytd === h1, '| H1==H2?', h1 === h2)
  const pick = (rows: string, name: string) => {
    const line = rows.split('\n').find(l => l.includes(name))
    return line ? line.replace(/\s+/g, ' ').trim().slice(0, 110) : 'NOT_FOUND'
  }
  for (const name of ['澳优', '项目集', '全域精准', '合计', '全表']) {
    console.log(`--- ${name} ---`)
    console.log('  YTD:', pick(ytd, name))
    console.log('  H1 :', pick(h1, name))
    console.log('  H2 :', pick(h2, name))
  }
})
