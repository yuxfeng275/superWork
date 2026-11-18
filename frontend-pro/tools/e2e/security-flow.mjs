#!/usr/bin/env node
/**
 * 安全流程 E2E：管理员重置密码 → 用户登录被强制改密 → 服务端门禁 → 改密后恢复。
 *
 * 只创建一个临时账号并在 finally 删除；密码不落盘、不打印。
 * 用法：ADMIN_PASSWORD=*** E2E_BASE=http://192.168.1.241:18080 node tools/e2e/security-flow.mjs
 */
import { chromium } from 'playwright';

const BASE = process.env.E2E_BASE || 'http://192.168.1.241:18080';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;
if (!ADMIN_PASSWORD) {
  console.error('缺少 ADMIN_PASSWORD');
  process.exit(2);
}

const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.E2E_CHROMIUM ||
    `${process.env.HOME}/Library/Caches/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-mac-arm64/chrome-headless-shell`,
});
const context = await browser.newContext();
const page = await context.newPage();
const errors = [];
page.on('console', (message) => {
  if (message.type() === 'error') errors.push(message.text());
});

const stamp = Date.now();
const username = `sec_e2e_${stamp}`;
const initial = `Init-${stamp}`;
const changed = `Changed-${stamp}`;
let userId;
let adminToken;

async function api(path, method = 'GET', body, token) {
  const response = await context.request.fetch(`${BASE}${path}`, {
    method,
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    data: body ? JSON.stringify(body) : undefined,
  });
  return { status: response.status(), body: await response.json().catch(() => null) };
}

async function main() {
  const login = await api('/api/auth/login', 'POST', { username: 'admin', password: ADMIN_PASSWORD });
  if (login.status !== 200) throw new Error(`admin 登录失败 ${login.status}`);
  adminToken = login.body.data.accessToken;

  const created = await api('/api/users', 'POST', {
    username, password: initial, realName: '安全流程 E2E', role: 'DIRECTOR', status: 1,
  }, adminToken);
  if (created.status !== 200) throw new Error(`创建测试账号失败 ${created.status}`);
  userId = created.body.data.id;

  const reset = await api(`/api/users/${userId}/reset-password`, 'POST', undefined, adminToken);
  const oneTime = reset.body?.data?.password;
  if (reset.status !== 200 || !oneTime) throw new Error('重置密码失败');

  const flagLogin = await api('/api/auth/login', 'POST', { username, password: oneTime });
  if (flagLogin.body?.data?.passwordChangeRequired !== true) throw new Error('登录未返回强制改密标记');
  const token = flagLogin.body.data.accessToken;
  const blocked = await api('/api/users', 'GET', undefined, token);
  if (blocked.status !== 403) throw new Error(`业务 API 未被门禁拦截：${blocked.status}`);

  await page.goto(`${BASE}/user/login`, { waitUntil: 'domcontentloaded' });
  await page.getByPlaceholder('请输入账号').fill(username);
  await page.getByPlaceholder('请输入密码').fill(oneTime);
  await page.getByRole('button', { name: /进入系统/ }).click();
  await page.waitForURL(/change-password/, { timeout: 20000 });

  await page.getByLabel('当前密码').fill(oneTime);
  await page.getByLabel('新密码', { exact: true }).fill(changed);
  await page.getByLabel('确认新密码').fill(changed);
  await page.getByRole('button', { name: /修改并重新登录/ }).click();
  await page.waitForURL(/user\/login/, { timeout: 20000 });

  const oldLogin = await api('/api/auth/login', 'POST', { username, password: oneTime });
  const newLogin = await api('/api/auth/login', 'POST', { username, password: changed });
  if (oldLogin.status !== 400 || newLogin.status !== 200) throw new Error('改密后旧/新密码状态不正确');
  if (newLogin.body?.data?.passwordChangeRequired === true) throw new Error('改密后标记未清除');

  console.log('✅ 安全流程 E2E 通过：重置 → 强制改密 → 403 门禁 → 改密 → 恢复');
}

try {
  await main();
} finally {
  if (userId && adminToken) {
    await api(`/api/users/${userId}`, 'DELETE', undefined, adminToken).catch(() => undefined);
  }
  await browser.close();
}
