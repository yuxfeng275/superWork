import { expect, test, type Page, type Route } from '@playwright/test'

const apiResult = (data: unknown) => JSON.stringify({
  code: 200,
  message: 'success',
  data,
  timestamp: new Date().toISOString()
})

const loggedIn = async (page: Page) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('user', JSON.stringify({
      id: 9,
      username: 'mail.owner',
      realName: '邮件员工',
      role: 'FULL_STACK_ENGINEER'
    }))
  })
  await page.route('**/api/requirements**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: apiResult({ records: [], total: 0 })
  }))
}

const fulfill = (route: Route, data: unknown) => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: apiResult(data)
})

const configuredAccount = {
  configured: true,
  enabled: true,
  provider: 'ALIBABA_CLOUD_ENTERPRISE_MAIL',
  emailAddress: 'owner@example.com',
  credentialConfigured: true,
  connectionStatus: 'CONNECTED',
  lastTestedAt: '2026-08-17T09:00:00',
  lastSyncAt: '2026-08-17T10:00:00',
  lastSyncStatus: 'SUCCESS',
  lastSyncMessage: '同步完成'
}

const digest = {
  id: 31,
  businessDate: '2026-08-16',
  status: 'READY',
  generationMode: 'AI',
  overview: '共收到 2 封邮件，1 封需要今天处理。',
  mailCount: 2,
  importantItems: [{ messageId: 101, title: '合同确认', content: '客户等待最终确认' }],
  todos: [{ messageId: 101, title: '回复合同', content: '今天 18:00 前回复' }],
  risks: [{ messageId: 102, title: '交付风险', content: '上线窗口尚未确认' }],
  replySuggestions: [{ messageId: 101, title: '建议回复', content: '已收到，我们将在今天确认。' }],
  generatedAt: '2026-08-17T08:02:00',
  pushStatus: 'SUCCESS',
  pushMessage: '已推送'
}

const messages = {
  records: [
    {
      id: 101,
      messageId: '<owner-101@example.com>',
      subject: '合同确认',
      fromName: '张经理',
      fromAddress: 'zhang@customer.example',
      receivedAt: '2026-08-16T15:20:00',
      preview: '请确认最终合同版本',
      hasAttachments: true,
      attachmentCount: 1
    },
    {
      id: 102,
      messageId: '<owner-102@example.com>',
      subject: '项目上线窗口',
      fromName: '项目组',
      fromAddress: 'pm@example.com',
      receivedAt: '2026-08-16T10:00:00',
      preview: '上线窗口待确认',
      hasAttachments: false,
      attachmentCount: 0
    }
  ],
  total: 2,
  size: 20,
  current: 1,
  pages: 1
}

const mockConfiguredPage = async (page: Page) => {
  await page.route('**/api/emails/account', route => fulfill(route, configuredAccount))
  await page.route('**/api/emails/digests**', route => fulfill(route, digest))
  await page.route('**/api/emails/messages**', route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/emails/messages/101/interpretation') {
      return fulfill(route, {
        status: 'SUCCESS',
        summary: '客户要求今天确认最终合同版本。',
        senderIntent: '推动合同确认并进入签署流程',
        keyPoints: ['最终合同版本待确认', '邮件包含合同附件'],
        actionItems: [{ content: '回复合同确认结果', deadline: '今天 18:00', priority: '高' }],
        risks: ['逾期回复可能影响签署时间'],
        replySuggestion: '已收到最终版本，我们将在今天 18:00 前回复确认结果。',
        model: 'deepseek-v4-flash',
        generatedAt: '2026-08-17T16:30:00'
      })
    }
    if (path === '/api/emails/messages/101') {
      return fulfill(route, {
        ...messages.records[0],
        toAddresses: ['owner@example.com'],
        ccAddresses: [],
        textBody: '<img src=x onerror="window.__emailXss=true">\n您好，\n请确认最终合同版本。',
        attachments: [{ fileName: '合同.pdf', contentType: 'application/pdf', size: 4096 }],
        interpretation: { status: 'NOT_GENERATED', keyPoints: [], actionItems: [], risks: [] }
      })
    }
    if (path === '/api/emails/messages/102') {
      return fulfill(route, {
        ...messages.records[1],
        toAddresses: ['owner@example.com'],
        ccAddresses: ['delivery@example.com'],
        textBody: '请确认项目上线窗口。',
        attachments: [],
        interpretation: { status: 'NOT_GENERATED', keyPoints: [], actionItems: [], risks: [] }
      })
    }
    return fulfill(route, messages)
  })
  await page.route('**/api/emails/sync/status', route => fulfill(route, { status: 'IDLE' }))
}

test.beforeEach(async ({ page }) => {
  await loggedIn(page)
})

test('未配置用户使用企业邮箱和第三方客户端安全密码绑定，且不提交归属字段', async ({ page }) => {
  let accountReads = 0
  let submitted: Record<string, unknown> | undefined
  await page.route('**/api/emails/account', async route => {
    if (route.request().method() === 'PUT') {
      submitted = route.request().postDataJSON() as Record<string, unknown>
      return fulfill(route, configuredAccount)
    }
    accountReads += 1
    return fulfill(route, accountReads === 1
      ? { configured: false, enabled: false, provider: 'ALIBABA_CLOUD_ENTERPRISE_MAIL', credentialConfigured: false }
      : configuredAccount)
  })
  await page.route('**/api/emails/digests**', route => fulfill(route, { ...digest, status: 'EMPTY', mailCount: 0 }))
  await page.route('**/api/emails/messages**', route => fulfill(route, { records: [], total: 0, size: 20, current: 1, pages: 0 }))
  await page.route('**/api/emails/sync/status', route => fulfill(route, { status: 'IDLE' }))

  await page.goto('/emails')
  await expect(page.getByRole('heading', { name: '绑定阿里云企业邮箱' })).toBeVisible()
  await expect(page.getByText('第三方客户端安全密码', { exact: false }).first()).toBeVisible()
  await page.getByLabel('企业邮箱地址').fill('owner@example.com')
  await page.getByLabel('第三方客户端安全密码').fill('app-password-secret')
  await page.getByRole('button', { name: '保存并绑定' }).click()

  await expect.poll(() => submitted).toEqual({
    emailAddress: 'owner@example.com',
    appPassword: 'app-password-secret'
  })
  expect(submitted).not.toHaveProperty('userId')
  expect(submitted).not.toHaveProperty('ownerUserId')
  await expect(page.getByText('owner@example.com').first()).toBeVisible()
  await page.getByRole('button', { name: '账户设置' }).click()
  await expect(page.getByLabel('第三方客户端安全密码')).toHaveValue('')
})

test('配置后展示摘要与收件箱，并仅以纯文本打开当前列表邮件', async ({ page }) => {
  await mockConfiguredPage(page)
  const requestedMessagePaths: string[] = []
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path.startsWith('/api/emails/messages/')) requestedMessagePaths.push(path)
  })

  await page.goto('/emails')
  await expect(page.getByRole('link', { name: '邮件管理' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '摘要总览' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByText('共收到 2 封邮件，1 封需要今天处理。')).toBeVisible()
  await page.getByRole('tab', { name: /重要邮件 1/ }).click()
  await expect(page.getByRole('region', { name: '重要邮件' })).toContainText('合同确认')
  await page.getByRole('tab', { name: /待办事项 1/ }).click()
  await expect(page.getByRole('region', { name: '待办事项' })).toContainText('今天 18:00 前回复')
  await page.getByRole('tab', { name: /风险提醒 1/ }).click()
  await expect(page.getByRole('region', { name: '风险提醒' })).toContainText('上线窗口尚未确认')
  await page.getByRole('tab', { name: /回复建议 1/ }).click()
  await expect(page.getByRole('region', { name: '回复建议' })).toContainText('已收到，我们将在今天确认。')
  await expect(page.getByLabel('收件箱列表')).toContainText('张经理')

  await page.getByLabel('收件箱列表').getByText('合同确认').click()
  const drawer = page.getByRole('dialog', { name: '邮件详情' })
  await expect(drawer).toContainText('安全阅读模式')
  await expect(drawer).toContainText('<img src=x onerror="window.__emailXss=true">')
  await expect(drawer).toContainText('合同.pdf')
  await expect(drawer).toContainText('application/pdf')
  await expect(drawer.getByText('PDF', { exact: true })).toBeVisible()
  await expect(drawer.getByRole('button', { name: '上一封邮件' })).toBeDisabled()
  await drawer.getByRole('tab', { name: /AI 解读/ }).click()
  await expect(drawer.getByText('客户要求今天确认最终合同版本。')).toBeVisible()
  await expect(drawer.getByText('回复合同确认结果')).toBeVisible()
  await expect(drawer.getByText('逾期回复可能影响签署时间')).toBeVisible()
  await expect(drawer.getByText('deepseek-v4-flash', { exact: true })).toBeVisible()
  await drawer.getByRole('button', { name: '下一封邮件' }).click()
  await expect(drawer.getByRole('heading', { name: '项目上线窗口' })).toBeVisible()
  await expect(drawer).toContainText('delivery@example.com')
  await expect(drawer.locator('img')).toHaveCount(0)
  await expect.poll(() => page.evaluate(() => (window as typeof window & { __emailXss?: boolean }).__emailXss)).toBeUndefined()
  expect(requestedMessagePaths).toEqual(['/api/emails/messages/101', '/api/emails/messages/101/interpretation', '/api/emails/messages/102'])
  expect(requestedMessagePaths.some(path => path.includes('/users/'))).toBe(false)
})

test('立即同步只调用当前用户端点并展示运行到成功状态', async ({ page }) => {
  await mockConfiguredPage(page)
  let syncPosts = 0
  let statusReads = 0
  await page.unroute('**/api/emails/sync/status')
  await page.route('**/api/emails/sync', route => {
    syncPosts += 1
    expect(route.request().postData()).toBeNull()
    expect(new URL(route.request().url()).searchParams.has('userId')).toBe(false)
    return fulfill(route, { status: 'RUNNING', startedAt: '2026-08-17T10:10:00' })
  })
  await page.route('**/api/emails/sync/status', route => {
    statusReads += 1
    return fulfill(route, statusReads === 1
      ? { status: 'IDLE' }
      : statusReads === 2
        ? { status: 'RUNNING', startedAt: '2026-08-17T10:10:00' }
        : { status: 'SUCCESS', startedAt: '2026-08-17T10:10:00', finishedAt: '2026-08-17T10:10:01', syncedCount: 2, message: '新增 2 封邮件' })
  })

  await page.goto('/emails')
  await page.getByRole('button', { name: '立即同步' }).click()
  await expect(page.getByRole('button', { name: '同步中…' })).toBeDisabled()
  await expect(page.getByText('新增 2 封邮件')).toBeVisible({ timeout: 5000 })
  expect(syncPosts).toBe(1)
})

test('规则降级和空收件箱具有独立可用状态', async ({ page }) => {
  await page.route('**/api/emails/account', route => fulfill(route, configuredAccount))
  await page.route('**/api/emails/digests**', route => fulfill(route, {
    ...digest,
    status: 'DEGRADED',
    generationMode: 'RULES',
    overview: '智能摘要暂不可用，已按规则整理。',
    mailCount: 0,
    importantItems: [],
    todos: [],
    risks: [],
    replySuggestions: [],
    pushStatus: 'UNMAPPED',
    pushMessage: '未找到企业微信用户映射'
  }))
  await page.route('**/api/emails/messages**', route => fulfill(route, { records: [], total: 0, size: 20, current: 1, pages: 0 }))
  await page.route('**/api/emails/sync/status', route => fulfill(route, { status: 'IDLE' }))

  await page.goto('/emails')
  await expect(page.getByText('规则降级', { exact: true })).toBeVisible()
  await expect(page.getByText('未找到企业微信用户映射')).toBeVisible()
  await expect(page.getByText('当前筛选条件下暂无邮件')).toBeVisible()
  await expect(page.getByRole('button', { name: '立即同步' })).toBeEnabled()

  await page.setViewportSize({ width: 390, height: 844 })
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
  expect(overflow).toBe(false)
})
