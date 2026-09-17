import { expect, test } from '@playwright/test'

const mockBusinessLines = [
  { id: 1, name: '全渠道云鹿定制', description: '定制业务', status: 1 }
]

const mockProjects = [
  { id: 11, businessLineId: 1, parentId: null, level: 1, name: '皇家项目', fullPath: '皇家项目', code: 'ROYAL', managerId: 1, status: 1 },
  { id: 12, businessLineId: 1, parentId: 11, level: 2, name: 'PMS', fullPath: '皇家项目/PMS', code: 'ROYAL-PMS', managerId: 1, status: 1 }
]

const mockUsers = [
  { id: 1, username: 'pm_zhang', realName: '张项目经理', role: 'PM', status: 1 },
  { id: 2, username: 'product_wang', realName: '王产品经理', role: 'PRODUCT', status: 1 }
]

const mockRequirements = [
  {
    id: 1,
    reqNo: 'REQ-2026-0001',
    title: '用户管理优化',
    type: '项目需求',
    businessLineId: 1,
    projectId: 12,
    status: '待评估',
    priority: '高',
    source: '客户',
    creatorId: 1,
    createdAt: '2026-04-10T00:00:00'
  }
]

const mockCustomerContacts = [
  { id: 101, projectId: 11, name: 'Ember', company: '皇家集团', isActive: 1 }
]

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem(
      'user',
      JSON.stringify({
        id: 999,
        username: 'admin',
        realName: '系统管理员',
        role: 'BU_ADMIN'
      })
    )
  })

  await page.route('**/api/business-lines**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: mockBusinessLines }, timestamp: new Date().toISOString() })
    })
  })

  await page.route('**/api/projects**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: mockProjects }, timestamp: new Date().toISOString() })
    })
  })

  await page.route('**/api/users**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: mockUsers }, timestamp: new Date().toISOString() })
    })
  })

  await page.route('**/api/requirements**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: mockRequirements }, timestamp: new Date().toISOString() })
    })
  })

  await page.route('**/api/customer-contacts**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: { records: mockCustomerContacts }, timestamp: new Date().toISOString() })
    })
  })
})

test('新建需求弹窗调整字段顺序并优化控件外观', async ({ page }) => {
  await page.goto('/requirements')
  await page.getByRole('button', { name: '新建需求' }).click()

  const rightLabels = await page.locator('.create-modal .form-right .form-label').allTextContents()
  const typeIndex = rightLabels.findIndex(text => text.includes('需求类型'))
  const actorIndex = rightLabels.findIndex(text => text.includes('提出人'))
  expect(typeIndex).toBeLessThan(actorIndex)

  await expect(page.locator('.create-modal .spotlight-group')).toHaveCount(1)
  await expect(page.locator('.create-modal .field-shell.select-shell')).toHaveCount(1)
  await expect(page.locator('.create-modal .field-shell.date-shell')).toHaveCount(1)

  const dateInput = page.locator('.create-modal .date-input')
  await expect(dateInput).toHaveClass(/polished-input/)
  await expect(page.locator('.create-modal .polished-input')).toHaveCount(2)
})

test('切换为项目需求后展示客户信息并限制富文本图片尺寸', async ({ page }) => {
  await page.goto('/requirements')
  await page.getByRole('button', { name: '新建需求' }).click()

  const projectTypeOption = page.locator('.create-modal .spotlight-group .radio-item').filter({ hasText: '项目需求' })
  await projectTypeOption.click()
  const switchedLabels = await page.locator('.create-modal .form-right .form-label').allTextContents()
  const switchedTypeIndex = switchedLabels.findIndex(text => text.includes('需求类型'))
  const customerIndex = switchedLabels.findIndex(text => text.includes('客户信息'))
  expect(switchedTypeIndex).toBeLessThan(customerIndex)
  await expect(page.locator('.create-modal')).toContainText('客户信息')
  await expect(page.locator('.create-modal .field-shell.select-shell')).toHaveCount(2)
  await expect(page.locator('.create-modal .polished-select')).toHaveCount(2)

  await page.locator('.project-card-modal').first().click()

  await page.locator('.editor-content').evaluate(node => {
    node.innerHTML = '<p>测试图片</p><img src=\"data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==\" style=\"width: 2000px; height: 1400px;\" />'
  })

  const imageInfo = await page.locator('.editor-content img').evaluate(node => {
    const style = window.getComputedStyle(node)
    return {
      maxWidth: style.maxWidth,
      maxHeight: style.maxHeight
    }
  })

  expect(imageInfo.maxWidth).toBe('100%')
  expect(imageInfo.maxHeight).toBe('220px')
})
