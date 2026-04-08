import { test, expect } from '@playwright/test';

const login = async (page) => {
  await page.goto('/login');
  await page.waitForTimeout(1000);
  const inputs = page.locator('.login-form.active input');
  await inputs.nth(0).fill('admin');
  await inputs.nth(1).fill('123456');
  await page.locator('.login-form.active .submit-btn').click();
  await page.waitForTimeout(2000);
};

test('角色管理 - 配置授权弹窗交互测试', async ({ page }) => {
  await login(page);
  await page.goto('/system/roles');
  await page.waitForTimeout(1500);

  // Verify role table loads
  const roleCount = await page.locator('.el-table__body tr').count();
  expect(roleCount).toBeGreaterThan(0);

  // Click "配置授权" button
  await page.locator('button:has-text("配置授权")').first().click();
  await page.waitForTimeout(1500);

  // Verify auth dialog opens
  const dialog = page.locator('.el-dialog:has-text("配置授权")');
  await expect(dialog).toBeVisible();

  // Verify left side has menu tree with checkboxes
  const menuTree = page.locator('.auth-menu .el-tree');
  await expect(menuTree).toBeVisible();

  const treeNodes = await page.locator('.auth-menu .el-tree-node__content').count();
  expect(treeNodes).toBeGreaterThan(0);

  // Verify right side has button panel
  const buttonPanel = page.locator('.auth-buttons');
  await expect(buttonPanel).toBeVisible();

  // Click first tree node content to trigger @node-click and show its buttons
  const firstTreeNode = page.locator('.auth-menu .el-tree-node__content').first();
  await firstTreeNode.click({ force: true });
  await page.waitForTimeout(500);

  // After clicking, should show button list with menu name header
  const buttonMenuName = page.locator('.button-menu-name');
  await expect(buttonMenuName).toBeVisible();
  const text = await buttonMenuName.textContent();
  expect(text).not.toBeNull();

  // Verify checkbox can be toggled via the visible label
  const firstCheckboxLabel = page.locator('.auth-menu .el-tree-node__content .el-checkbox').first();
  await firstCheckboxLabel.click({ force: true });
  await page.waitForTimeout(200);

  // Verify save button exists
  const saveBtn = page.locator('.el-dialog button:has-text("保存")');
  await expect(saveBtn).toBeVisible();
});

test('角色管理 - 保存授权功能', async ({ page }) => {
  await login(page);
  await page.goto('/system/roles');
  await page.waitForTimeout(1500);

  // Click config auth
  await page.locator('button:has-text("配置授权")').first().click();
  await page.waitForTimeout(1500);

  // Toggle a checkbox in tree via visible label
  const firstCheckboxLabel = page.locator('.auth-menu .el-tree-node__content .el-checkbox').first();
  await firstCheckboxLabel.click({ force: true });
  await page.waitForTimeout(300);

  // Save
  await page.locator('button:has-text("保存")').click();
  await page.waitForTimeout(2000);

  // Check success message
  const successMsg = page.locator('.el-message--success');
  await expect(successMsg).toBeVisible();
});
