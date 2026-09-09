import { expect, test } from '@playwright/test'
test('生产 H1 H2 selectedPeriod 状态检查', async ({ page }) => {
  test.setTimeout(120000)
  await page.goto('/')
  await page.waitForTimeout(800)
  await page.locator('input[type="text"]').first().fill('admin')
  await page.locator('input[type="password"]').first().fill('123456')
  await page.getByRole('button', { name: /登录|登 录/ }).click()
  await page.waitForTimeout(1200)
  await page.getByText('营收管理', { exact: true }).first().click()
  await page.waitForTimeout(1500)
  await page.getByRole('tab', { name: '交付与利润' }).click()
  await page.waitForTimeout(1500)
  const tbody = page.locator('.matrix-table tbody').last()
  const rows = await tbody.locator('tr').allInnerTexts()
  const auYou = rows.find(r => r.includes('澳优')) || ''
  // 读取切换按钮 active 状态
  const buttons = page.locator('.delivery-toolbar .segment-switch').first().locator('button')
  const btnCount = await buttons.count()
  for (let i = 0; i < btnCount; i++) {
    console.log(`btn${i} text=`, await buttons.nth(i).innerText(), 'class=', await buttons.nth(i).getAttribute('class'))
  }
  console.log('澳优行(ytd):', auYou.replace(/\s+/g, ' ').slice(0, 120))
  // 点击 H1
  await buttons.nth(0).click()
  await page.waitForTimeout(400)
  console.log('点击第1个按钮后 active 状态:')
  for (let i = 0; i < btnCount; i++) {
    console.log(`btn${i} class=`, await buttons.nth(i).getAttribute('class'))
  }
  const rowsH1 = await tbody.locator('tr').allInnerTexts()
  console.log('澳优行(点击H1后):', (rowsH1.find(r => r.includes('澳优')) || '').replace(/\s+/g, ' ').slice(0, 120))
  // 点击 H2
  await buttons.nth(1).click()
  await page.waitForTimeout(400)
  console.log('点击第2个按钮后 active 状态:')
  for (let i = 0; i < btnCount; i++) {
    console.log(`btn${i} class=`, await buttons.nth(i).getAttribute('class'))
  }
  const rowsH2 = await tbody.locator('tr').allInnerTexts()
  console.log('澳优行(点击H2后):', (rowsH2.find(r => r.includes('澳优')) || '').replace(/\s+/g, ' ').slice(0, 120))
})
