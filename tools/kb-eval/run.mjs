#!/usr/bin/env node
/**
 * 知识库检索评估：把 corpus.mjs 的 24 篇短文以 OWNER 范围入库，跑 24 个问句，
 * 统计 top1 / top5 / MRR，并可选验证项目范围的召回与隔离。
 *
 * 用法（不要用命令行传密码，密码只从环境变量读）：
 *   KB_EVAL_PASSWORD=*** node tools/kb-eval/run.mjs \
 *     --base http://192.168.1.241:18080 --user admin \
 *     --project 1 --other-project 2
 *
 * 默认跑完会把评估文档停用（enabled=0），不污染后续检索；--keep 保留启用状态。
 * 需要管理岗位账号（入库接口只对管理岗位开放）。
 */
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { corpus } from './corpus.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith('--')) continue;
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith('--')) {
      args[key] = true;
    } else {
      args[key] = next;
      i += 1;
    }
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));
const base = String(args.base || 'http://192.168.1.241:18080').replace(/\/+$/, '');
const username = String(args.user || 'admin');
const password = process.env.KB_EVAL_PASSWORD;
const keep = Boolean(args.keep);
const projectId = args.project ? Number(args.project) : null;
const otherProjectId = args['other-project'] ? Number(args['other-project']) : 2;
const limit = args.limit ? Number(args.limit) : 5;

if (!password) {
  console.error('缺少 KB_EVAL_PASSWORD 环境变量（密码不要写进命令行参数）');
  process.exit(2);
}

let token = '';

async function api(path, { method = 'GET', body, auth = true, allowStatus = [] } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(`${base}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    payload = { raw: text };
  }
  if (allowStatus.includes(response.status)) {
    return { status: response.status, payload };
  }
  if (!response.ok || (payload && payload.code !== undefined && payload.code !== 200)) {
    throw new Error(
      `${method} ${path} -> HTTP ${response.status} ${payload?.code ?? ''} ${payload?.message ?? text}`,
    );
  }
  return { status: response.status, payload };
}

async function login() {
  const { payload } = await api('/api/auth/login', {
    method: 'POST',
    auth: false,
    body: { username, password },
  });
  token = payload.data.accessToken;
  return payload.data.userInfo;
}

async function ingest(doc, scopeType, scopeId) {
  const { payload } = await api('/api/knowledge/documents', {
    method: 'POST',
    body: {
      title: doc.title,
      content: doc.content,
      sourceType: 'MANUAL',
      sourceRef: `kb-eval-${scopeType.toLowerCase()}-${doc.id}`,
      scopeType,
      scopeId,
      tags: 'kb-eval',
    },
  });
  return payload.data.id;
}

async function search(query, project) {
  const scope = project ? `&projectId=${project}` : '';
  const { payload } = await api(
    `/api/knowledge/search?q=${encodeURIComponent(query)}&limit=${limit}${scope}`,
  );
  return payload.data || [];
}

function rankOf(hits, expected) {
  const index = hits.findIndex((hit) => String(hit.documentTitle || '').includes(expected));
  return index < 0 ? null : index + 1;
}

async function main() {
  const user = await login();
  console.log(`登录成功：${user.username || user.nickname || username} (id=${user.id})`);

  const rows = [];
  const documentIds = [];
  for (const doc of corpus) {
    const id = await ingest(doc, 'OWNER', user.id);
    documentIds.push(id);
    const hits = await search(doc.query);
    const rank = rankOf(hits, doc.expect);
    rows.push({ id: doc.id, query: doc.query, expect: doc.expect, rank, top: hits[0]?.documentTitle ?? null });
    console.log(`${rank ? '✅' : '❌'} ${doc.id} rank=${rank ?? '-'}  ${doc.query}`);
  }

  const total = rows.length;
  const top1 = rows.filter((row) => row.rank === 1).length;
  const top5 = rows.filter((row) => row.rank !== null && row.rank <= limit).length;
  const mrr = rows.reduce((sum, row) => sum + (row.rank ? 1 / row.rank : 0), 0) / total;
  const misses = rows.filter((row) => row.rank === null);

  const report = {
    generatedAt: new Date().toISOString(),
    base,
    scope: 'OWNER',
    total,
    limit,
    top1,
    top5,
    top1Rate: Number((top1 / total).toFixed(4)),
    top5Rate: Number((top5 / total).toFixed(4)),
    mrr: Number(mrr.toFixed(4)),
    misses,
    rows,
    projectCheck: null,
  };

  if (projectId) {
    const doc = {
      id: 'project-01',
      title: 'KB EVAL 项目范围测试文档',
      content: '这是一篇只属于指定项目的知识库评估文档，关键词是项目范围隔离验证。',
      query: '项目范围隔离验证',
    };
    const projectDocId = await ingest(doc, 'PROJECT', projectId);
    documentIds.push(projectDocId);

    const withProject = await search(doc.query, projectId);
    const withoutProject = await search(doc.query);
    const otherProject = await search(doc.query, otherProjectId);
    let deniedStatus = null;
    try {
      await search(doc.query, 999999);
    } catch (error) {
      deniedStatus = error.message;
    }

    report.projectCheck = {
      projectId,
      otherProjectId,
      withProjectHit: withProject.some((hit) => String(hit.documentTitle).includes('KB EVAL')),
      withoutProjectHit: withoutProject.some((hit) => String(hit.documentTitle).includes('KB EVAL')),
      otherProjectHit: otherProject.some((hit) => String(hit.documentTitle).includes('KB EVAL')),
      inaccessibleProjectDenied: Boolean(deniedStatus),
      inaccessibleProjectError: deniedStatus,
    };
    console.log('项目范围校验:', report.projectCheck);
  }

  if (!keep) {
    for (const id of documentIds) {
      await api(`/api/knowledge/documents/${id}/enabled?value=false`, { method: 'POST' });
    }
    console.log(`已停用 ${documentIds.length} 篇评估文档（enabled=0，可随时在管理页重新启用）`);
  }

  if (!existsSync(HERE)) mkdirSync(HERE, { recursive: true });
  const file = join(HERE, `report-${new Date().toISOString().replace(/[:.]/g, '-')}.json`);
  writeFileSync(file, `${JSON.stringify(report, null, 2)}\n`, 'utf8');

  console.log('\n===== 基线 =====');
  console.log(`top1 ${top1}/${total} = ${(report.top1Rate * 100).toFixed(1)}%`);
  console.log(`top${limit} ${top5}/${total} = ${(report.top5Rate * 100).toFixed(1)}%`);
  console.log(`MRR ${report.mrr}`);
  if (misses.length) console.log('未命中:', misses.map((row) => row.id).join(', '));
  console.log(`报告: ${file}`);
}

main().catch((error) => {
  console.error('评估失败:', error.message);
  process.exit(1);
});
