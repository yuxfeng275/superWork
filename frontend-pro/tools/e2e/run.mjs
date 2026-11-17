#!/usr/bin/env node
/**
 * 241 E2E：浏览器 + 接口混合验证（不回写业务状态、不触发真实群发）。
 *
 * 登录走 API 拿 token，再注入 localStorage，避免在浏览器里输入密码；
 * 每一步截图存 screenshots/，最后生成 report.json / report.md。
 *
 * 用法：E2E_PASSWORD=*** node tools/e2e/run.mjs
 */
import { chromium } from 'playwright';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const SHOTS = join(HERE, 'screenshots');
const BASE = process.env.E2E_BASE || 'http://192.168.1.241:18080';
const USER = process.env.E2E_USER || 'admin';
const PASSWORD = process.env.E2E_PASSWORD || '123456';

mkdirSync(SHOTS, { recursive: true });

const report = {
  base: BASE,
  user: USER,
  startedAt: new Date().toISOString(),
  steps: [],
  consoleErrors: [],
  serverErrors: [],
};

function record(name, ok, detail, screenshot) {
  report.steps.push({ name, ok, detail, screenshot });
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? `：${detail}` : ''}`);
}

async function shot(page, name) {
  const file = join(SHOTS, `${name}.png`);
  await page.screenshot({ path: file, fullPage: false });
  return file;
}

const cachedChromium = join(
  homedir(),
  'Library/Caches/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-mac-arm64/chrome-headless-shell',
);
const executablePath = process.env.E2E_CHROMIUM || (existsSync(cachedChromium) ? cachedChromium : undefined);
const browser = await chromium.launch({ headless: true, executablePath });
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
const page = await context.newPage();
page.on('pageerror', (error) => report.consoleErrors.push(String(error)));
page.on('console', (message) => {
  if (message.type() === 'error') report.consoleErrors.push(message.text());
});
page.on('response', (response) => {
  const status = response.status();
  const url = response.url();
  if (status >= 500 || (status === 404 && url.includes('/api/'))) {
    report.serverErrors.push(`${status} ${url}`);
  }
});

try {
  // ---------- 登录：API 拿 token，注入 localStorage ----------
  const login = await context.request.post(`${BASE}/api/auth/login`, {
    data: { username: USER, password: PASSWORD },
  });
  const loginBody = await login.json();
  if (login.status() !== 200 || loginBody.code !== 200) {
    throw new Error(`登录失败：HTTP ${login.status()} ${loginBody.message || ''}`);
  }
  const { accessToken, userInfo } = loginBody.data;
  await context.addInitScript(
    ({ token, user }) => {
      localStorage.setItem('token', token);
      localStorage.setItem('refreshToken', '');
      localStorage.setItem('user', JSON.stringify(user));
    },
    { token: accessToken, user: userInfo },
  );
  record('登录', true, `HTTP ${login.status()}，用户 ${userInfo.username || USER}`);

  // ---------- 1. 工作台：晨报 + 待评审 ----------
  try {
    await page.goto(`${BASE}/workbench`, { waitUntil: 'domcontentloaded' });
    await page.getByText('待我评审').first().waitFor({ timeout: 20000 });
    await page.waitForTimeout(3000);
    const body = await page.locator('body').innerText();
    const briefVisible = body.includes('今日安排') && body.includes('E2E 验证：晨报链路');
    record('工作台打开', briefVisible,
      briefVisible ? '待我评审 + 今日晨报可见' : '待我评审可见，但未看到今日晨报',
      await shot(page, '01-workbench'));
  } catch (error) {
    record('工作台打开', false, String(error.message), await shot(page, '01-workbench-failed'));
  }

  // ---------- 2. 建议「去做」→ 带草稿进助手 ----------
  try {
    // 建议是异步扫描出来的，等按钮出现再判断，避免刚进页面就断言为空
    await page.waitForTimeout(8000);
    const go = page.getByRole('button', { name: /去\s*做/ }).first();
    if ((await go.count()) === 0) {
      record('建议去做', false, '当前没有建议条（可能都被处理或条件不满足）');
    } else {
      await go.click();
      await page.waitForURL(/ai-assistant/, { timeout: 15000 });
      await page.waitForTimeout(3000);
      const value = await page.locator('textarea').first().inputValue();
      record('建议去做', value.trim().length > 10, `预填 ${value.length} 字`, await shot(page, '02-suggestion-draft'));
    }
  } catch (error) {
    record('建议去做', false, String(error.message), await shot(page, '02-suggestion-failed'));
  }

  // ---------- 3. 助手：Jev 状态 + 一轮真实对话 ----------
  try {
    await page.goto(`${BASE}/ai-assistant`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(5000);
    // 没有活动会话时 Sender 是禁用的：先点「新建对话」
    const textareaBefore = page.locator('textarea').first();
    if (await textareaBefore.isDisabled().catch(() => false)) {
      await page.getByText('新建对话').first().click();
      await page.waitForTimeout(2500);
    }
    const body = await page.locator('body').innerText();
    const jevVisible = body.includes('Jev 已启用') || body.includes('Jev 未启用');
    const textarea = page.locator('textarea').first();
    await textarea.fill('列出我负责的未完成任务，最多 5 条');
    const beforeBubbles = await page.locator('.ant-bubble').count();
    await textarea.press('Enter');
    await page.waitForTimeout(25000);
    const afterBubbles = await page.locator('.ant-bubble').count();
    const after = await page.locator('body').innerText();
    const replied = afterBubbles > beforeBubbles || /暂无|任务|没有/.test(after.slice(body.length));
    record('助手对话', jevVisible && replied,
      `${jevVisible ? 'Jev 状态可见' : '未见 Jev 状态'}；气泡 ${beforeBubbles}→${afterBubbles}`,
      await shot(page, '03-assistant-chat'));
  } catch (error) {
    record('助手对话', false, String(error.message), await shot(page, '03-assistant-failed'));
  }

  // ---------- 4. 知识库检索 ----------
  try {
    await page.goto(`${BASE}/knowledge`, { waitUntil: 'domcontentloaded' });
    await page.getByText('语料健康').or(page.getByText('知识库').first()).first().waitFor({ timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(2500);
    const input = page.getByPlaceholder(/输入关键词/).first();
    await input.fill('会员通 交付周期');
    await page.getByRole('button', { name: '检索' }).first().click();
    await page.waitForTimeout(3000);
    const body = await page.locator('body').innerText();
    const hit = body.includes('会员通') && body.includes('命中') === false ? body.includes('会员通') : body.includes('会员通');
    record('知识库检索', hit, '检索「会员通 交付周期」', await shot(page, '04-knowledge'));
  } catch (error) {
    record('知识库检索', false, String(error.message), await shot(page, '04-knowledge-failed'));
  }

  // ---------- 5. 团队效能 ----------
  try {
    await page.goto(`${BASE}/team-efficiency`, { waitUntil: 'domcontentloaded' });
    await page.getByText('投递率').first().waitFor({ timeout: 15000 });
    await page.waitForTimeout(2500);
    const rows = await page.locator('.ant-table-row').count();
    record('团队效能', rows > 0, `${rows} 行数据`, await shot(page, '05-team-efficiency'));
  } catch (error) {
    record('团队效能', false, String(error.message), await shot(page, '05-team-failed'));
  }

  // ---------- 6. 制品评审详情 ----------
  try {
    await page.goto(`${BASE}/workbench`, { waitUntil: 'domcontentloaded' });
    await page.getByText('待我评审').first().waitFor({ timeout: 20000 });
    await page.waitForTimeout(2500);
    const item = page.locator('.sw-workbench-review').locator('button, a').filter({ hasText: /.+/ }).first();
    if ((await item.count()) === 0) {
      record('制品评审', false, '评审队列为空，无法打开详情');
    } else {
      await item.click();
      await page.waitForTimeout(2000);
      const body = await page.locator('body').innerText();
      const drawer = (await page.locator('.ant-drawer').count()) > 0;
      record('制品评审', drawer, drawer ? '详情抽屉已打开' : '未出现详情抽屉', await shot(page, '06-artifact-review'));
    }
  } catch (error) {
    record('制品评审', false, String(error.message), await shot(page, '06-artifact-failed'));
  }
} finally {
  report.finishedAt = new Date().toISOString();
  // 步骤全过且没有服务端 5xx/API 404 才算通过；控制台告警单独列出，不掩盖也不冒充失败
  report.ok = report.steps.every((step) => step.ok) && report.serverErrors.length === 0;
  writeFileSync(join(HERE, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
  const md = [
    '# 241 E2E 报告',
    '',
    `- 地址：${BASE}`,
    `- 账号：${USER}`,
    `- 开始：${report.startedAt}`,
    `- 结束：${report.finishedAt}`,
    `- 结果：${report.ok ? '全部通过' : '有失败项'}`,
    '',
    '| 步骤 | 结果 | 说明 | 截图 |',
    '|---|---|---|---|',
    ...report.steps.map((step) =>
      `| ${step.name} | ${step.ok ? '✅' : '❌'} | ${step.detail || ''} | ${step.screenshot ? step.screenshot.split('/').pop() : ''} |`),
    '',
    '## 服务端错误',
    '',
    report.serverErrors.length ? report.serverErrors.map((line) => `- ${line}`).join('\n') : '无',
    '',
    '## 控制台告警（不计入失败）',
    '',
    report.consoleErrors.length
      ? `共 ${report.consoleErrors.length} 条，首条：${report.consoleErrors[0].split('\n')[0]}`
      : '无',
    '',
  ].join('\n');
  writeFileSync(join(HERE, 'report.md'), md);
  await browser.close();
  console.log(`报告：${join(HERE, 'report.md')}`);
  process.exit(report.ok ? 0 : 1);
}
