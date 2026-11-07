#!/usr/bin/env node
/**
 * agent-autoloop 的 command 通道：把一轮自动化执行交给合拍后端。
 *
 * 用法（rules.json 里 command 数组）：node sidecar-agent.mjs "{prompt}"
 *
 * 环境变量：
 *   HUJI_BASE_URL       合拍后端地址，如 http://backend:8080
 *   HUJI_TOKEN          project-context.token（请求头 X-Sidecar-Token）
 *   HUJI_AGENT_USER_ID  以谁的身份跑（决定工具可见范围，必须是真实成员）
 *   HUJI_PROJECT_ID     目标项目 id（必填）
 *   HUJI_TASK_ID        可选，agent-autoloop 任务号，写进会话便于 why 回溯
 *
 * 约定：向 stdout 打印最后一条结论；失败时打印原因并以非 0 退出。
 * 中台只看最后一条回复，因此不要在 stdout 混入日志（日志走 stderr）。
 */

const baseUrl = (process.env.HUJI_BASE_URL || "").replace(/\/+$/, "");
const token = process.env.HUJI_TOKEN || "";
const userId = Number(process.env.HUJI_AGENT_USER_ID || 0);
const projectId = Number(process.env.HUJI_PROJECT_ID || 0);
const taskId = process.env.HUJI_TASK_ID || "";
const prompt = process.argv.slice(2).join(" ").trim();

function fail(message) {
  console.error(message.startsWith("失败") ? message : `失败：${message}`);
  process.exit(1);
}

if (!baseUrl) fail("缺少 HUJI_BASE_URL");
if (!userId) fail("缺少 HUJI_AGENT_USER_ID（自动化必须以真实成员身份运行）");
if (!prompt) fail("缺少提示词");
if (!projectId) fail("缺少 HUJI_PROJECT_ID（夜间任务必须指定目标项目）");

const controller = new AbortController();
const timer = setTimeout(() => controller.abort(), 15 * 60 * 1000);

try {
  const response = await fetch(`${baseUrl}/internal/project-context/run`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { "X-Sidecar-Token": token } : {}),
    },
    body: JSON.stringify({ userId, projectId, title: taskId || "自动任务", prompt }),
    signal: controller.signal,
  });

  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    fail(`HTTP ${response.status} ${payload?.message || ""}`.trim());
  }

  const result = payload?.data;
  if (!result) fail("后端返回为空");
  if (result.error) fail(result.error);

  const text = (result.text || "").trim();
  console.log(text || `本轮无结论（runId=${result.runId}）`);
} catch (error) {
  fail(error?.name === "AbortError" ? "运行超时（>15 分钟）" : error?.message || String(error));
} finally {
  clearTimeout(timer);
}
