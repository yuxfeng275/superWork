import { expect, test } from '@playwright/test'

// 生产冒烟：业务线利润页（真实登录、真实数据）
test('业务线利润页生产冒烟', async ({ page }) => {
  test.setTimeout(120000)
  await page.goto('/')
  await page.waitForTimeout(1000)
  await page.locator('input[placeholder*="用户名"], input[type="text"]').first().fill('admin')
  await page.locator('input[type="password"]').first().fill('123456')
  await page.getByRole('button', { name: /登录|登 录/ }).click()
  await page.waitForTimeout(2000)

  // 侧边栏：数据分析分区含「业务线利润」
  await page.getByRole('button', { name: '数据分析', exact: true }).first().click()
  await page.waitForTimeout(500)
  const panel = page.locator('.secondary-panel')
  await panel.getByRole('button', { name: '业务线利润', exact: true }).click()

  await expect(page).toHaveURL(/\/bl-profit/)
  const table = page.locator('.profit-table')
  await expect(table).toBeVisible()
  // 真实数据：月份行 + YTD 小计 + 合计行
  await expect(table).toContainText('2026-08')
  await expect(table.locator('tr.ytd-row').first()).toContainText('YTD')
  await expect(table.locator('tr.grand-total-row')).toContainText('合计')
})
