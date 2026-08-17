import { expect, test } from '@playwright/test'

const mockWorkflowConfigs = [
  { id: 1, requirementType: '项目需求', fromStatus: '待评估', toStatus: '评估中', conditionType: '开始评估', allowedRoles: '["解决方案经理"]', isActive: 1, sortOrder: 1 },
  { id: 2, requirementType: '项目需求', fromStatus: '评估中', toStatus: '待设计', conditionType: '评估通过', allowedRoles: '["解决方案经理"]', isActive: 1, sortOrder: 2 },
  { id: 3, requirementType: '项目需求', fromStatus: '评估中', toStatus: '已拒绝', conditionType: '评估拒绝', allowedRoles: '["解决方案经理"]', isActive: 1, sortOrder: 3 }
]

const mockStatusOptions = {
  项目需求: ['待评估', '评估中', '已拒绝', '待设计'],
  产品需求: ['待评估', '评估中', '待设计']
}

test.beforeEach(async ({ page }) => {
  let updatePayload: unknown = null
  let deleteUrl = ''

  await page.addInitScript(() => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem(
      'user',
      JSON.stringify({ id: 1, username: 'admin', realName: '系统管理员', role: 'DIRECTOR' })
    )
  })

  await page.route('**/api/requirements**', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: { records: [], total: 0 },
        timestamp: new Date().toISOString()
      })
    })
  })

  await page.route('**/api/workflow-configs/meta/status-options', async route => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: mockStatusOptions, timestamp: new Date().toISOString() })
    })
  })

  await page.route('**/api/workflow-configs', async route => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: mockWorkflowConfigs, timestamp: new Date().toISOString() })
      })
      return
    }

    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: null, timestamp: new Date().toISOString() })
      })
      return
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: null, timestamp: new Date().toISOString() })
    })
  })

  await page.route('**/api/workflow-configs/*', async route => {
    if (route.request().method() === 'PUT') {
      updatePayload = JSON.parse(route.request().postData() || '{}')
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: null, timestamp: new Date().toISOString() })
      })
      return
    }

    if (route.request().method() === 'DELETE') {
      deleteUrl = route.request().url()
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'success', data: null, timestamp: new Date().toISOString() })
      })
      return
    }

    await route.fallback()
  })

  await page.exposeFunction('getWorkflowTestState', () => ({ updatePayload, deleteUrl }))
})

test('工作流配置以拖拽画布展示全部合法状态和分支', async ({ page }) => {
  await page.goto('/system/workflow')

  await expect(page.locator('.canvas-board')).toBeVisible()
  await expect(page.locator('[data-testid="workflow-state-node"]')).toHaveCount(4)
  await expect(page.locator('[data-testid="workflow-state-node"][data-status="待评估"]')).toBeVisible()
  await expect(page.locator('[data-testid="workflow-state-node"][data-status="评估中"]')).toContainText('2 条流出')
  await expect(page.locator('.edge-badge')).toContainText(['开始评估', '评估通过', '评估拒绝'])
})

test('拖拽关联状态时打开规则弹窗，且无需手输状态名', async ({ page }) => {
  await page.goto('/system/workflow')

  const sourceHandle = page.locator('[data-testid="workflow-state-node"][data-status="待评估"] .connect-handle')
  const targetNode = page.locator('[data-testid="workflow-state-node"][data-status="待设计"]')
  const sourceBox = await sourceHandle.boundingBox()
  const targetBox = await targetNode.boundingBox()

  if (!sourceBox || !targetBox) throw new Error('missing workflow node positions')

  await page.mouse.move(sourceBox.x + sourceBox.width / 2, sourceBox.y + sourceBox.height / 2)
  await page.mouse.down()
  await page.mouse.move(targetBox.x + targetBox.width / 2, targetBox.y + targetBox.height / 2, { steps: 10 })
  await page.mouse.up()

  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('新增流转规则')
  await expect(dialog).toContainText('源状态')
  await expect(dialog).toContainText('目标状态')
  await expect(dialog.locator('[data-testid="workflow-from-status"] input')).toHaveValue('待评估')
  await expect(dialog.locator('[data-testid="workflow-to-status"] input')).toHaveValue('待设计')
  await expect(dialog.locator('input[placeholder*="状态"]')).toHaveCount(0)
})

test('编辑已有连线时可保存 payload 且支持删除', async ({ page }) => {
  await page.goto('/system/workflow')

  await page.locator('.edge-badge', { hasText: '评估通过' }).click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await dialog.locator('[data-testid="workflow-condition-field"] input').fill('评估通过并进入设计')
  await dialog.locator('[data-testid="workflow-roles-field"]').getByText('经营负责人').click()
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(page.locator('.el-message--success')).toBeVisible()

  const stateAfterSave = await page.evaluate(() => (window as any).getWorkflowTestState())
  expect(stateAfterSave.updatePayload.fromStatus).toBe('评估中')
  expect(stateAfterSave.updatePayload.toStatus).toBe('待设计')
  expect(stateAfterSave.updatePayload.conditionType).toBe('评估通过并进入设计')
  expect(stateAfterSave.updatePayload.allowedRoles).toContain('经营负责人')

  await page.locator('.edge-badge', { hasText: '评估拒绝' }).click()
  await dialog.getByRole('button', { name: '删除' }).click()
  const confirmDialog = page.locator('.el-message-box')
  await expect(confirmDialog).toBeVisible()
  await confirmDialog.locator('.el-button--primary').click()
  await expect(page.locator('.el-message--success').last()).toBeVisible()

  const stateAfterDelete = await page.evaluate(() => (window as any).getWorkflowTestState())
  expect(stateAfterDelete.deleteUrl).toContain('/api/workflow-configs/3')
})
