import { expect, test, type Route } from '@playwright/test'

const result = (data: unknown) => JSON.stringify({
  code: 200,
  message: 'success',
  data,
  timestamp: new Date().toISOString()
})

const fulfill = (route: Route, data: unknown) => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: result(data)
})

const group = {
  groupCode: 'email-integration',
  groupName: '邮件摘要与推送',
  description: '管理邮件摘要模型、企业微信内部应用与系统链接',
  items: [
    { key: 'deepseek.enabled', name: '启用 DeepSeek', valueType: 'BOOLEAN', value: 'true', sensitive: false, configured: true, required: true, sortOrder: 10 },
    { key: 'deepseek.base-url', name: 'DeepSeek 服务地址', valueType: 'URL', value: 'https://api.deepseek.com', sensitive: false, configured: true, required: true, sortOrder: 20 },
    { key: 'deepseek.model', name: 'DeepSeek 模型', valueType: 'STRING', value: 'deepseek-chat', sensitive: false, configured: true, required: true, sortOrder: 30 },
    { key: 'deepseek.api-key', name: 'DeepSeek API Key', valueType: 'PASSWORD', sensitive: true, configured: true, required: false, sortOrder: 40 },
    { key: 'wecom.enabled', name: '启用企业微信推送', valueType: 'BOOLEAN', value: 'true', sensitive: false, configured: true, required: true, sortOrder: 50 },
    { key: 'wecom.secret', name: '企业微信 Secret', valueType: 'PASSWORD', sensitive: true, configured: true, required: false, sortOrder: 90 }
  ]
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'manager-token')
    localStorage.setItem('user', JSON.stringify({ id: 10, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' }))
  })
})

test('配置管理统一展示配置组且敏感值不回显', async ({ page }) => {
  await page.route('**/api/system/configs', route => fulfill(route, [{
    groupCode: 'email-integration', groupName: '邮件摘要与推送', description: group.description,
    itemCount: 6, configuredCount: 6
  }]))
  await page.route('**/api/system/configs/email-integration', route => fulfill(route, group))

  await page.goto('/system/configs')

  await expect(page.getByRole('heading', { name: '配置管理', level: 2 })).toBeVisible()
  await expect(page.getByText('邮件摘要与推送', { exact: true }).first()).toBeVisible()
  await expect(page.getByLabel('DeepSeek API Key')).toHaveValue('')
  await expect(page.getByLabel('企业微信 Secret')).toHaveValue('')
  await expect(page.getByPlaceholder('已配置；留空保持不变')).toHaveCount(2)
})

test('配置管理保存配置项时不提交用户ID且保存后清空密钥', async ({ page }) => {
  let submitted: Record<string, unknown> | undefined
  await page.route('**/api/system/configs', route => fulfill(route, [{
    groupCode: 'email-integration', groupName: '邮件摘要与推送', description: group.description,
    itemCount: 6, configuredCount: 6
  }]))
  await page.route('**/api/system/configs/email-integration', async route => {
    if (route.request().method() === 'PUT') submitted = route.request().postDataJSON() as Record<string, unknown>
    return fulfill(route, group)
  })
  await page.goto('/system/configs')
  await page.getByLabel('DeepSeek API Key').fill('replacement-secret')
  await page.getByRole('button', { name: '保存配置' }).click()

  await expect.poll(() => submitted).toMatchObject({ values: { 'deepseek.api-key': 'replacement-secret' } })
  expect(submitted).not.toHaveProperty('userId')
  expect(submitted).not.toHaveProperty('updatedBy')
  await expect(page.getByLabel('DeepSeek API Key')).toHaveValue('')
})
