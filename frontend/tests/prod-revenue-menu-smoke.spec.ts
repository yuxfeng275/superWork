import { expect, test } from '@playwright/test'

// 生产冒烟：营收菜单拆分后的侧边栏结构与四个页面可用性（真实登录、真实接口）
test('营收菜单拆分生产冒烟', async ({ page }) => {
  test.setTimeout(120000)
  await page.goto('/')
  await page.waitForTimeout(1000)
  await page.locator('input[placeholder*="用户名"], input[type="text"]').first().fill('admin')
  await page.locator('input[type="password"]').first().fill('123456')
  await page.getByRole('button', { name: /登录|登 录/ }).click()
  await page.waitForTimeout(2000)

  // 侧边栏：数据分析分区下出现新菜单，旧「营收管理」消失
  await page.getByRole('button', { name: '数据分析', exact: true }).first().click()
  await page.waitForTimeout(500)
  const panel = page.locator('.secondary-panel')
  await expect(panel.getByRole('button', { name: '工时 & 成本', exact: true })).toBeVisible()
  await expect(panel.getByRole('button', { name: '交付与利润', exact: true })).toBeVisible()
  await expect(panel.getByRole('button', { name: '配置', exact: true })).toBeVisible()
  await expect(panel.getByRole('button', { name: '营收管理', exact: true })).toHaveCount(0)
  // 配置分组默认展开：三级菜单可见
  await expect(panel.getByRole('button', { name: '数据导入', exact: true })).toBeVisible()
  await expect(panel.getByRole('button', { name: '待映射与销售项目', exact: true })).toBeVisible()

  // 菜单跳转：工时 & 成本
  await panel.getByRole('button', { name: '工时 & 成本', exact: true }).click()
  await expect(page).toHaveURL(/\/revenue\/worktime/)
  await expect(page.locator('.revenue-page h2')).toHaveText('工时 & 成本')
  await expect(page.locator('.revenue-page .matrix-table')).toBeVisible()

  // 菜单跳转：交付与利润
  await panel.getByRole('button', { name: '交付与利润', exact: true }).click()
  await expect(page).toHaveURL(/\/revenue\/delivery/)
  await expect(page.locator('.revenue-page h2')).toHaveText('交付与利润')
  await expect(page.locator('.revenue-page .matrix-table')).toBeVisible()

  // 菜单跳转：数据导入（三级）
  await panel.getByRole('button', { name: '数据导入', exact: true }).click()
  await expect(page).toHaveURL(/\/revenue\/import/)
  await expect(page.locator('.revenue-page h2')).toHaveText('数据导入')
  await expect(page.locator('.revenue-page').getByRole('heading', { name: '合同导入', exact: true })).toBeVisible()

  // 菜单跳转：待映射与销售项目（三级）
  await panel.getByRole('button', { name: '待映射与销售项目', exact: true }).click()
  await expect(page).toHaveURL(/\/revenue\/pending/)
  await expect(page.locator('.revenue-page h2')).toHaveText('待映射与销售项目')
  await expect(page.locator('.revenue-page').getByRole('heading', { name: /合同待映射/ })).toBeVisible()

  // 旧路径重定向兼容
  await page.goto('/revenue')
  await expect(page).toHaveURL(/\/revenue\/worktime/)
})
