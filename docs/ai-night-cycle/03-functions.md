# 合拍 AI 功能设计（功能 → 页面 → 接口 → 表）

> 目标见 [01-architecture.md](01-architecture.md)，流程见 [02-flow.md](02-flow.md)。
> 本文的每一项功能都必须能追到「页面 + 接口 + 表」，否则不做。

## 一、功能清单

| # | 功能 | 页面/入口 | 接口 | 主表 | 优先级 |
|---|---|---|---|---|---|
| F1 | 文件投递（拖拽/邮件转发/目录同步） | 工作台「项目收件箱」/ 邮件详情「转入项目」 | `POST /api/ai-files`、`POST /internal/project-context/files`、`POST /api/ai-files/from-email` | `ai_file`、`project_fact` | P0 |
| F2 | 文件空间浏览与原文下载 | 项目详情 →「文件」页签 | `GET /api/ai-files?projectId=`、`GET /api/ai-files/{id}/content` | `ai_file` | P0 |
| F3 | 文件摘要入事实层 | 无独立页面（在 F1 流程内） | 内部服务（`FactSummaryService` 复用） | `project_fact(source_type=FILE_UPLOAD)` | P0 |
| F4 | 今日同步巡检（18:30） | 工作台「待补料」卡 | `POST /internal/project-context/sync-check` | `ai_run`(kind=SYNC_CHECK) | P1 |
| F5 | 夜间批编排（22:00） | 运行面 | `POST /api/ai-runs`、`GET /api/ai-runs` | `ai_run`、`ai_run_node`、`ai_invocation` | P0 |
| F6 | SOP 定义与版本 | 管理端「夜间流程」配置页 | `GET/POST /api/ai-sops`、`POST /api/ai-sops/{id}/publish` | `ai_sop`、`ai_sop_version` | P1 |
| F7 | 执行器插槽 | 无页面（配置项） | 内部接口 `AgentExecutor` | — | P0 |
| F8 | 人工闸门（审批/打回/重跑） | 工作台「待我审批」+ 运行面节点详情 | `POST /api/ai-gates/{id}/approve\|reject\|retry` | `ai_gate`、`ai_artifact(kind=DECISION)` | P0 |
| F9 | 制品评审（已有，扩字段） | 工作台「待我评审」 | 现有 `decideArtifact` / `applyArtifact` | `ai_artifact` | P0 |
| F10 | 晨报（已有，扩保护） | 工作台首屏「今日安排」 | 现有 `/api/ai-briefs`、`/internal/project-context/briefs` | `ai_brief` | P0 |
| F11 | 运行面 | 新页「AI 运行」 | `GET /api/ai-runs/{id}`、`GET /api/ai-runs/{id}/nodes`、`GET /api/ai-runs/{id}/diagnostics` | 全部 AI 表 | P0 |
| F12 | 失败诊断 | 运行面节点详情抽屉 | `GET /api/ai-runs/{id}/diagnostics` | `ai_run`、`ai_invocation` | P1 |
| F13 | 采纳回写事实 | 制品评审「采纳」 | 现有 `applyArtifact` 扩展 | `project_fact(source_type=ARTIFACT)` | P0 |
| F14 | 用量与预算 | 运行面「用量」 | `GET /api/ai-agent/usage`（已有） | `ai_agent_usage` | P2 |

## 二、权限矩阵

角色：成员（M）、主管（S）、评审人（R，可多人）、管理员（A）、合作方（P，仅项目内可见）。

| 功能 | M | S | R | A | P |
|---|---|---|---|---|---|
| 投递文件到参与项目 | ✅ | ✅ | ✅ | ✅ | ✅（仅自己被授权项目，`visibility=PRIVATE_COMPANY`） |
| 浏览/下载项目文件 | ✅ 参与项目 | ✅ 管辖项目 | ✅ 被指派 | ✅ 全部 | ⚠️ 仅自己投递或显式共享 |
| 写 SOP / 发布 | ❌ | ⚠️ 需 A 审核 | ❌ | ✅ | ❌ |
| 发起/取消 run | ❌ | ✅ 管辖项目 | ❌ | ✅ | ❌ |
| 审批 gate | ❌ | ✅ 管辖项目 | ✅ 被指派 | ✅ | ❌ |
| 制品评审 | ⚠️ 仅自己产出可见 | ✅ | ✅ 被指派 | ✅ | ❌ |
| 看运行面 | ⚠️ 仅自己发起的 run | ✅ 管辖 | ✅ 被指派 | ✅ | ❌ |
| 看他人晨报 | ❌ | ❌（晨报是私有的，一人一份） | ❌ | ⚠️ 运维排障需理由留痕 | ❌ |
| 改夜间写权限红线 | ❌ | ❌ | ❌ | ⚠️ 需代码评审，不可由配置放开 | ❌ |

硬规则（来自目标约束）：

- Agent 一律以**成员本人**身份运行（`ai_run.owner_user_id`），禁止服务账号跑夜间任务。
- 合作方只读、只在被授权项目内可见，`project_fact.visibility` 参与过滤（已有 `FactVisibilityPolicy`）。
- 夜间写权限不可配置放开：允许的写操作是代码里写死的白名单（建草稿/建任务/晨报/制品），与 Jev 的 `WRITE_TOOLS` 同源。

## 三、数据模型（DDL 草案）

### 3.1 统一空间

```sql
CREATE TABLE ai_file (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id    BIGINT       NOT NULL,
  owner_user_id BIGINT       NOT NULL COMMENT '投递人',
  filename      VARCHAR(300) NOT NULL,
  content_type  VARCHAR(120) NULL,
  size_bytes    BIGINT       NOT NULL,
  sha256        CHAR(64)     NOT NULL COMMENT '内容哈希，去重键',
  object_key    VARCHAR(300) NOT NULL COMMENT '存储键，<projectId>/<sha[:2]>/<sha>.<ext>',
  source        VARCHAR(24)  NOT NULL COMMENT 'UPLOAD/EMAIL/SYNC_DIR/AGENT',
  source_ref    VARCHAR(255) NULL COMMENT '邮件 id / 目录路径 / run 号',
  visibility    VARCHAR(16)  NOT NULL DEFAULT 'PROJECT' COMMENT 'PROJECT/PRIVATE_COMPANY/OWNER',
  deleted_at    DATETIME     NULL,
  created_at    DATETIME     NOT NULL,
  UNIQUE KEY uk_file_sha (project_id, sha256),
  KEY idx_file_project (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一空间：文件元数据（原文在对象存储）';
```

- 文件与事实的关联**不建中间表**：`project_fact.source_ref = CONCAT('file:', ai_file.id)`，`source_type='FILE_UPLOAD'`，`dedupe_hash=sha256('FILE_UPLOAD|file:<id>')`。理由：事实层是唯一真相，多一张关联表就多一套一致性。
- Agent 产出的文件（制品附件）走同一张表，`source='AGENT'`、`source_ref=<runId>/<nodeId>`。

### 3.2 编排内核

```sql
CREATE TABLE ai_run (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_no         VARCHAR(32)  NOT NULL COMMENT '对外编号，如 NR-20261122-01',
  kind           VARCHAR(24)  NOT NULL COMMENT 'NIGHT_BATCH/SYNC_CHECK/M2_QUEUE/ADHOC',
  sop_code       VARCHAR(64)  NOT NULL,
  sop_version    INT          NOT NULL,
  project_id     BIGINT       NULL,
  owner_user_id  BIGINT       NOT NULL COMMENT '以谁的身份跑',
  biz_date       DATE         NOT NULL,
  trigger_source VARCHAR(24)  NOT NULL COMMENT 'AUTOLOOP/CRON/API/EVENT',
  status         VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/WAITING_HUMAN/SUCCEEDED/PARTIAL/FAILED/CANCELLED',
  epoch          INT          NOT NULL DEFAULT 0 COMMENT '租约代次，防旧 worker 覆盖',
  lease_owner    VARCHAR(64)  NULL,
  lease_expires_at DATETIME   NULL,
  attempt        INT          NOT NULL DEFAULT 0,
  summary        VARCHAR(1000) NULL,
  started_at     DATETIME     NULL,
  finished_at    DATETIME     NULL,
  created_at     DATETIME     NOT NULL,
  updated_at     DATETIME     NOT NULL,
  UNIQUE KEY uk_run_biz (kind, sop_code, project_id, owner_user_id, biz_date),
  KEY idx_run_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次流程实例';

CREATE TABLE ai_run_node (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id        BIGINT       NOT NULL,
  node_key      VARCHAR(64)  NOT NULL COMMENT 'SOP 内节点标识',
  seq           INT          NOT NULL,
  executor_kind VARCHAR(16)  NOT NULL COMMENT 'sidecar/dsh-acp/cli',
  status        VARCHAR(16)  NOT NULL COMMENT 'PENDING/READY/RUNNING/SUCCEEDED/FAILED/WAITING_GATE/UNKNOWN',
  attempt       INT          NOT NULL DEFAULT 0,
  max_attempt   INT          NOT NULL DEFAULT 2,
  rework_count  INT          NOT NULL DEFAULT 0,
  epoch         INT          NOT NULL DEFAULT 0,
  deps          VARCHAR(300) NULL COMMENT 'JSON: 前驱 node_key 列表',
  gate_required TINYINT      NOT NULL DEFAULT 0,
  executor_session_id VARCHAR(120) NULL COMMENT 'dsh/sidecar 会话号，用于续跑',
  input_json    JSON         NULL,
  output_json   JSON         NULL,
  error_code    VARCHAR(64)  NULL,
  error_message VARCHAR(1000) NULL,
  started_at    DATETIME     NULL,
  finished_at   DATETIME     NULL,
  UNIQUE KEY uk_node (run_id, node_key),
  KEY idx_node_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SOP 节点执行状态';

CREATE TABLE ai_invocation (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id        BIGINT       NOT NULL,
  node_id       BIGINT       NOT NULL,
  attempt       INT          NOT NULL,
  call_id       VARCHAR(64)  NOT NULL COMMENT '执行器给出的工具调用号',
  logical_action_key VARCHAR(120) NOT NULL COMMENT '幂等键：hash(tool+规范化参数+业务对象)',
  tool_name     VARCHAR(64)  NOT NULL,
  args_json     JSON         NOT NULL,
  status        VARCHAR(16)  NOT NULL COMMENT 'STARTED/SUCCEEDED/FAILED/UNKNOWN/REPLAYED',
  result_json   JSON         NULL,
  error_code    VARCHAR(64)  NULL,
  started_at    DATETIME     NOT NULL COMMENT '前置写入时间，早于任何副作用',
  finished_at   DATETIME     NULL,
  replayed_from BIGINT       NULL COMMENT '回放来源 invocation id',
  approval_json JSON         NULL COMMENT 'per-call 审批记录',
  UNIQUE KEY uk_inv_call (run_id, node_id, attempt, call_id),
  UNIQUE KEY uk_inv_action (run_id, logical_action_key),
  KEY idx_inv_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具调用前置记录与幂等键';

CREATE TABLE ai_gate (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id        BIGINT       NOT NULL,
  node_id       BIGINT       NOT NULL,
  status        VARCHAR(16)  NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/APPROVED_RETRY/EXPIRED',
  assignee_user_id BIGINT    NOT NULL,
  requested_from_status VARCHAR(16) NOT NULL COMMENT '提交时的 node.status（乐观校验）',
  requested_attempt INT      NOT NULL,
  requested_rework  INT      NOT NULL,
  question      VARCHAR(500) NULL,
  decision      VARCHAR(1000) NULL COMMENT '通过理由/打回原因',
  reject_to     VARCHAR(64)  NULL COMMENT '打回目标 node_key',
  artifact_id   BIGINT       NULL COMMENT 'decision 制品',
  decided_by    BIGINT       NULL,
  decided_at    DATETIME     NULL,
  due_at        DATETIME     NULL,
  created_at    DATETIME     NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工闸门';

CREATE TABLE ai_sop (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  code       VARCHAR(64)  NOT NULL,
  name       VARCHAR(120) NOT NULL,
  enabled    TINYINT      NOT NULL DEFAULT 1,
  UNIQUE KEY uk_sop_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_sop_version (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  sop_id     BIGINT       NOT NULL,
  version    INT          NOT NULL,
  status     VARCHAR(16)  NOT NULL COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
  graph_json JSON         NOT NULL COMMENT 'nodes/edges/start/terminal，与 LoopForge 的 Skill Card 对齐',
  published_at DATETIME   NULL,
  UNIQUE KEY uk_sop_ver (sop_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.3 既有表的最小改动

```sql
ALTER TABLE ai_artifact
  ADD COLUMN attempt INT NOT NULL DEFAULT 0,
  ADD COLUMN content_hash CHAR(64) NULL,
  ADD COLUMN run_id BIGINT NULL,
  ADD COLUMN node_id BIGINT NULL,
  ADD COLUMN upstream_artifact_ids VARCHAR(300) NULL;

ALTER TABLE ai_brief
  ADD COLUMN source_run_id BIGINT NULL,
  ADD COLUMN overwrite_locked TINYINT NOT NULL DEFAULT 0 COMMENT '已分发=1，非夜间链路不许覆盖';

CREATE INDEX idx_artifact_run ON ai_artifact (run_id, node_id);
```

不动：`project_fact`（只追加语义已够）、`ai_task`（保留为异步任务队列）、`ai_capability_call`（保留为能力调用度量）。

## 四、接口清单

### 4.1 新增

| 方法 | 路径 | 说明 | 鉴权 |
|---|---|---|---|
| POST | `/api/ai-files` | 上传文件到项目空间，落 `ai_file` + `project_fact` | 登录 + 项目成员 |
| GET | `/api/ai-files?projectId=&cursor=` | 文件列表（分页） | 登录 + 项目可见 |
| GET | `/api/ai-files/{id}/content` | 下载原文（`ObjectStore.open` 流式） | 同上，记审计 |
| POST | `/internal/project-context/files` | 邮件转发/目录同步入口（X-Sidecar-Token） | token |
| POST | `/api/ai-runs` | 建 run（幂等：`uk_run_biz`） | 主管/管理员 |
| GET | `/api/ai-runs?status=&from=&to=` | 运行列表 | 主管/管理员；成员仅自己 |
| GET | `/api/ai-runs/{id}` | run 详情（节点树 + 用量 + 摘要） | 同上 |
| GET | `/api/ai-runs/{id}/nodes` | 节点列表 | 同上 |
| GET | `/api/ai-runs/{id}/diagnostics` | 诊断包（首错、时间线、调用统计） | 同上 |
| POST | `/api/ai-gates/{id}/approve` | 通过（带期望值校验） | 被指派评审人 |
| POST | `/api/ai-gates/{id}/reject` | 打回（`reject_to` + 原因） | 同上 |
| POST | `/api/ai-gates/{id}/retry` | 重跑本节点 | 同上 |
| GET/POST | `/api/ai-sops`、`/api/ai-sops/{id}/publish` | SOP 定义与发布 | 管理员 |

### 4.2 改造

| 接口 | 现状 | 改造 |
|---|---|---|
| `POST /internal/project-context/run` | 系统提示硬编码「写晨报」，`AutomationRunService.SYSTEM_PROMPT` 固定 | 增加 `mode` 与 `systemPrompt` 入参；缺省仍为夜间默认（兼容旧规则）；`ai_brief` 覆盖保护 |
| `POST /api/ai-artifacts/{id}/apply` | 采纳即回写事实 | 增加 `run_id/node_id` 关联；`APPROVED → APPLIED` 时锁 `content_hash` |
| `POST /internal/project-context/briefs` | 直接写晨报 | 增加 `overwrite` 语义：已分发晨报需显式 `force=true`（仅管理员） |
| `GET /api/ai-agent/usage` | 会话级用量 | 增加按 run 聚合 |

## 五、必须先修的缺陷（阻塞多规则编排）

`AutomationRunService.java:49-58` 的 `SYSTEM_PROMPT` 末尾写着：

> 产出：先用 `save_daily_brief` 写一份给当前用户的晨报…

这段对所有规则生效，导致：

1. 试跑规则（`try.*`）也会写晨报 —— 实测 2026-11-21 22:07 覆盖了 22:00 夜间批写入的 2026-11-22 晨报（`ai_brief` id=21，`updated_at` 从 10:00:37 变 22:07:43）；
2. 未来任何非晨报产出的规则（出方案、出补丁、巡检）都会被强制写晨报，并互相覆盖同一行（`uk_brief_user_day`）。

修法：系统提示拆成「公共铁律（证据/禁写/身份）」+「本轮产出口径（由调用方传）」，`mode` 取 `NIGHT_BRIEF | PLAN | PATCH | CHECK`，缺省 `NIGHT_BRIEF` 保持向后兼容。

## 六、前端页面

| 页面 | 路径 | 内容 | 状态 |
|---|---|---|---|
| 项目文件页签 | `/projects/:id` 内新增 | 文件列表、上传、下载、来源与摘要 | 新增 |
| 工作台「项目收件箱」 | `/workbench` 新增卡 | 拖拽上传、待归类文件、投递结果回执 | 新增 |
| 工作台「待我审批」 | `/workbench` 现有卡扩展 | 新增 gate 类型（附节点上下文与上游产物） | 改造 |
| AI 运行 | `/ai-runs` 新页 | 列表（状态/项目/耗时/产物数）、详情（节点树、调用列表、诊断包下载） | 新增 |
| 夜间流程配置 | `/ai-sops` 新页（管理员） | SOP 节点编辑、发布、版本对比 | 新增（P1 可只做 JSON 编辑） |

## 七、验收清单（对应目标成功标准）

| 成功标准 | 功能证据 |
|---|---|
| 统一空间可用 | F1–F3：上传 → `ai_file` → `project_fact`（含 `source_ref`）→ 夜间任务读取 |
| 重复投递只一条事实 | `uk_file_sha` + `project_fact.dedupe_hash` |
| 崩溃后不重复写 | F5/F7：`ai_invocation` 前置写 + `UNKNOWN` + 恢复扫描 |
| 审批只生效一次 | F8：`ai_gate` 乐观校验（期望 status/attempt/rework） |
| 夜间闭环 5 工作日 | F5/F8/F10/F11：run 成功率、晨报送达率、审批时效取自库 |
| 采纳回写事实 | F13：`APPLIED → project_fact(ARTIFACT)` |
| autoloop 只做触发 | 规则里不出现多步指令；run 由 `uk_run_biz` 幂等 |
