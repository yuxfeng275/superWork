# 项目级智能体闭环（Project Agent Loop）实施方案

> 更新时间：2026-11-07 · 状态：**M0/M1 已落地**（事实层 / 自动化运行入口 / 晨报与推送 / 制品评审与工作台待评审卡 / autoloop 接入包 / 项目详情抽屉的代码仓库绑定界面），剩服务器侧安装 autoloop 与 agent 运行时
> 决策前提（用户 2026-10-24 确认）：①核心问题是项目信息分散在成员与各家合作方手里，缺少「项目唯一事实源」；②目标是让 AI 在夜间把产出类/执行类工作做完，白天人只做评审与判断；③技术上**以 agent-autoloop 为运行时骨架**，借鉴 DeepSeek super agent 的用法（dsh + 模型能力），**不自研调度/状态机/卡片/熔断**，接受由此带来的技术栈重构。
> 相关：[ai-autoloop-design.md](ai-autoloop-design.md)（机制评估与取舍明细，其「先 B 后 A」的结论已被本文取代）· [ai-capabilities.md](ai-capabilities.md)

## 一、一句话方案

**合拍负责「看见」和「落库」，agent-autoloop 负责「什么时候跑、跑完等谁拍板」，Agent 负责「真的动手」。**
三者用两条桥连接：`合拍 → autoloop` 的事件投递（HTTP source）与 `Agent → 合拍` 的 MCP 工具面。

```
白天：人 + Agent 一起收信息 ──► 项目事实层（唯一事实源，可溯源）
夜间：autoloop 定时/事件派活 ──► Agent（dsh / 侧车）──► 制品（方案·补丁·修复说明·清单）
次日 07:30：每人一份晨报 + 现成制品 ──► 人只做评审 ──► 采纳回流成新事实
```

## 二、职责切分（谁建什么、谁不建）

| 能力 | 归属 | 理由 |
|---|---|---|
| 规则匹配、定时、事件排队、并发与重试 | **agent-autoloop** | 它已经做好，且零依赖、单机可跑（README「内核的四个机制」） |
| 挂起零消耗 + 待拍板卡片 + 拍板后续原会话跑 | **agent-autoloop** | 这是它最值钱的部分，自研等于重写一遍状态机 |
| 只追加事实日志 + `why` 回溯 | **agent-autoloop**（任务级） | 合拍只需在自己的表里存项目事实与制品，不重复做任务日志 |
| 跳数熔断、指令数据分离 | **agent-autoloop** | 它的 hop TTL 与「事件只作资料」是现成护栏 |
| 项目事实层（来源、归属、可见性、证据链） | **合拍** | 这是业务域知识，外挂做不了 |
| 业务工具面（任务/需求/大事儿/报价/邮件/云效/工时） | **合拍**（暴露为 MCP server） | 工具执行必须留在有事务与权限的地方（现状：`AiAgentToolService.java:57,93` 与连接器工具） |
| 制品与评审队列、次日分发、采纳回流 | **合拍** | 用户面前的界面与审计在系统内 |
| 代码仓库操作（读 SQL/改补丁/开 PR） | **Agent（dsh 优先）** | 需要文件与命令能力；现有侧车只有业务工具，做不了 |
| 模型与凭据 | **合拍已有的模型注册表** | `AiModelConfigService` / `ai_model` 已支持多提供方、自带 baseUrl、代理 |

**明确不建**：不新建第二套调度器、第二套状态机、第二套通知中心、第二套任务日志。

## 三、目标架构

```mermaid
flowchart TB
  subgraph 合拍（Spring Boot + MySQL）
    F[("project_fact<br/>项目唯一事实源")]
    A1["来源适配器<br/>系统内/成员投递/合作方/Git"]
    M["MCP server<br/>业务工具面"]
    R[("ai_artifact<br/>制品 + 评审队列")]
    B[("ai_brief<br/>每人每日晨报")]
    W["工作台 / 企微"]
  end
  subgraph agent-autoloop（Node 24，单机）
    K["内核：规则·事件·排队·卡片·熔断·事实日志"]
  end
  subgraph Agent
    D["dsh（DeepSeek Harness）<br/>文件/命令/长任务"]
    S["ai-sidecar（command 通道）<br/>业务工具密集型"]
  end

  A1 --> F
  F -->|"项目日报/异常/待办候选"| K
  K -->|"派活（含 userId、项目、预算）"| D
  K -->|"派活"| S
  D -->|"MCP 调工具（只读 + 建草稿）"| M
  S -->|"toolCallbackUrl（现状）"| M
  M --> F
  D -->|"产出制品"| R
  S -->|"产出草稿"| R
  R -->|"晨报汇总"| B
  B --> W
  W -->|"评审/采纳/驳回"| R
  R -->|"采纳即新事实"| F
  W -->|"拍板回答"| K
```

## 四、四个插件方向怎么落

### 4.1 事件源
| 事件 | 产生方式 | 触发什么 |
|---|---|---|
| 夜间批（22:00） | autoloop `when.cron` | 按项目扫一遍事实层，析出待办/需方案/缺陷候选 |
| 晨报分发（07:30） | autoloop `when.cron` | 汇总夜间制品 → 每人一条 |
| 状态变化（会议纪要生成、周报生成失败、报价 SENT 超期、大事儿逾期） | **合拍** 定时器/业务钩子 `POST` 到 autoloop 的 HTTP source（`emit`） | 单件事的即时处置 |
| 成员投递（文件/邮件转发） | 合拍上传接口 | 落事实层后触发归类 |

**注意**：合拍已有 12 处 `@Scheduled`（`WeeklyReportScheduler.java:33`、`QuotationExpiryScheduler.java:18`、`AiProactivePushScheduler.java:23` 等）继续保留——它们是**业务定时**；**AI 自动化调度**整体交给 autoloop，两边不重复。

### 4.2 Agent 通道
| 通道 | 用于 | 配置 |
|---|---|---|
| `dsh`（DeepSeek Harness） | 夜间代码类：读仓库、写 SQL 补丁、跑测试、出修复说明 | 官方预览版，`dsh --profile headless {prompt}`；与 DeepSeek 模型同源 |
| `command`（包现有侧车） | 业务工具密集型：查任务/需求/大事儿、起草邮件与周报段落 | 一个 ~20 行 shim：`POST /v1/runs` → 打印末条回复 |
| （P3 可选）Claude Code / Codex | 需要更强编码能力时的备选 | 一行配置 |

同时**必须修侧车三个假数**（详见 [ai-autoloop-design.md](ai-autoloop-design.md) §2.3）：成本恒 0、输出 8k 硬顶、无视觉；以及接上 `sessionId`/`transformContext`/`continue()`。

### 4.3 通道
- **对外拍板**：autoloop 企微卡片；`hub.publicUrl` 指向合拍已有反代地址（对应现有 `app.public-base-url` 配置），否则手机点不开。
- **系统内**：工作台（`frontend-pro/src/pages/workbench`）承接晨报与评审，不走卡片。

### 4.4 交付
**不用它的 delivery 插件**（面向 GitHub PR 流水线）。合拍自有 Git 服务器与 SQL 仓库，交付 = 「制品进评审队列 → 人 approve → 合拍记录合并结果」。第 4 点（PR 范围/谁 approve）按用户意见后续再定，先做**项目绑定仓库**。

## 五、合拍侧要建的东西

### 5.1 项目事实层（P0，全部工作的地基）

```sql
-- 一条事实：进系统的一段原始信息 + 归属 + 证据
CREATE TABLE project_fact (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id   BIGINT       NOT NULL,            -- 归属项目（皇家项目等）
  source_type  VARCHAR(32)  NOT NULL,            -- MEETING/EMAIL/WORKITEM/OA/YUQUE/WORKLOG/QUOTATION/
                                                 -- KEY_MATTER/WEEKLY_REPORT/FILE_UPLOAD/CHAT_EXPORT/GIT
  source_ref   VARCHAR(255) NULL,                -- 源主键或 URL（点回证据的唯一入口）
  actor_id     BIGINT       NULL,                -- 谁产的（内部 userId 或外部成员）
  happened_at  DATETIME     NOT NULL,
  title        VARCHAR(300) NULL,
  body         MEDIUMTEXT   NULL,                -- 原文（指令数据分离：只作资料，不进提示词）
  digest       VARCHAR(1000) NULL,               -- 供模型消费的摘要
  visibility   VARCHAR(16)  NOT NULL,            -- PROJECT/PRIVATE_COMPANY/OWNER（跨公司可见性）
  dedupe_hash  CHAR(64)     NOT NULL,            -- 同一封邮件/同一条工作项不重复入
  created_at   DATETIME     NOT NULL,
  UNIQUE KEY uk_fact_hash (dedupe_hash),
  KEY idx_fact_project (project_id, happened_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 项目绑定仓库与基础设置（用户指定「先绑定就行」）
CREATE TABLE project_git_repo (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id  BIGINT       NOT NULL,
  name        VARCHAR(100) NOT NULL,
  repo_url    VARCHAR(500) NOT NULL,             -- 公司 Git 地址
  default_branch VARCHAR(100) NOT NULL DEFAULT 'main',
  work_dir    VARCHAR(300) NULL,                 -- 只允许 agent 动的目录（如 sql/）
  agent       VARCHAR(32)  NOT NULL DEFAULT 'dsh',
  enabled     TINYINT      NOT NULL DEFAULT 1,
  created_at  DATETIME     NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**来源适配器清单（按接入成本排序）**

| 来源 | 现状 | 动作 |
|---|---|---|
| 会议纪要 | `MeetingService`：`UPLOADED→DRAFT→CONFIRMED`，待办 `DRAFT→CREATED` | 直接映射，落 fact |
| 周报 / KPI | `WeeklyReportService` 已有事实采集 | 直接映射 |
| 大事儿 | `BuKeyMatterService` 六态 + 周更表 | 直接映射 |
| 邮件 | `EmailDigestService` + 本地库 | 按项目关键词/参与人归属 |
| 云效工作项 | `YunxiaoIntegrationService.java:335` 每小时同步 | 直接映射（含项目映射表） |
| OA / 工时 / 报价 | 连接器已就绪（`QuotationService.java:38` 四态 + `EXPIRED`） | 直接映射 |
| **成员本地文件** | 无 | **投递入口**：项目收件箱（拖拽/邮件转发）+ 后续桌面目录同步器 |
| 合作方材料 | 无 | 同上，走 `visibility=PRIVATE_COMPANY` |
| **Git 仓库** | 无 | 提交与变更摘要入 fact（不是全量代码） |

### 5.2 分析层（P1）
在事实层上做**确定性候选 + LLM 归纳**：
- 确定性：未闭环决策（会议有决议但无工作项）、无人认领待办（纪要待办 `DRAFT` 超 48h）、缺方案商机（报价 `SENT` 且无跟进记录）、缺陷堆积（云效缺陷未流转）、风险升级（大事儿「有风险/已阻塞」超 N 天、周报缺更）。
- LLM 只在候选上做三件事：合并同类、判断该出方案还是该修 bug、写清「为什么现在做」。
- 每条候选必须挂 `source_ref`，否则不许进夜间任务（**无证据不派活**）。

### 5.3 制品与评审（P1~P2）
```sql
CREATE TABLE ai_artifact (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id  BIGINT      NOT NULL,
  task_id     VARCHAR(64) NULL,                  -- autoloop 的任务/轮次号，回溯用
  kind        VARCHAR(24) NOT NULL,              -- PLAN(方案)/PATCH(SQL补丁)/FIX_NOTE(修复说明)/
                                                 -- REVIEW_FIX(审查意见处理)/CHECKLIST(清单)
  title       VARCHAR(300) NOT NULL,
  content     MEDIUMTEXT  NULL,                  -- 方案正文 / 补丁说明 / diff 摘要
  evidence    JSON        NULL,                  -- 指向 project_fact.id 列表
  repo_id     BIGINT      NULL,                  -- 代码类制品绑定仓库
  branch      VARCHAR(120) NULL,                 -- 夜间产出分支，永不直接改主分支
  status      VARCHAR(16) NOT NULL,              -- DRAFT/IN_REVIEW/APPROVED/REJECTED/APPLIED
  reviewer_id BIGINT      NULL,
  decided_at  DATETIME    NULL,
  created_at  DATETIME    NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 5.4 晨报与回流（P1）
```sql
CREATE TABLE ai_brief (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT   NOT NULL,
  project_id  BIGINT   NULL,
  brief_date  DATE     NOT NULL,
  content     JSON     NOT NULL,                 -- {items:[{title,why,artifactIds,links}]}
  delivered_at DATETIME NULL,                    -- 站内/企微送达时间
  created_at  DATETIME NOT NULL,
  UNIQUE KEY uk_brief_user_day (user_id, brief_date, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
- 分发：07:30 每人一条企微 + 工作台首屏；**一人一条，不群发**。
- 回流：`APPROVED` → 制品落库并回写事实层（采纳即新事实）；`REJECTED` + 原因 → 作为规则好坏信号（复用 `ai_proactive_suggestion` 的采纳率口径，不新造指标体系）。

### 5.5 业务工具面（MCP server）
- 把现有工具面暴露为 MCP：`AiAgentToolService.definitions()`（`:93`）与连接器工具（`:57`）已经是一份现成的工具清单 + JSON Schema，**逐条映射即可**，不重写实现。
- 后端目前只有 MCP **客户端**（`integration/YuqueMcpClient.java`，最小 JSON-RPC 2.0），server 侧可沿用同一套 JSON-RPC 约定。
- **写工具白名单不变**（`JevDecisionService.java:26-29` 的门禁口径），并加一条夜间硬约束：**夜间只允许「建草稿/建任务」这类可回滚写，不允许对外发送、不允许改状态为完成**。

## 六、身份与权限（最容易出事的地方，先定死）

| 项 | 决定 |
|---|---|
| Agent 跑在谁的身份下 | **成员本人**（工具可见范围 = 他的可见范围）；规则里固化 `userId`，MCP 请求头带该用户凭证 |
| 合作方成员 | 进系统，按项目授权；`project_fact.visibility` 控制跨公司可见性 |
| 夜间任务的越权红线 | 不得用管理员/服务账号跑；不得跨项目读事实 |
| 卡片拍板 | 只能由该任务 `owner_user_id` 回答（回调核对） |

## 七、里程碑与验收

| 里程碑 | 内容 | 验收（可观测） |
|---|---|---|
| **M0 打通最小回路**（后端已完成，部署待做） | ✅ `project_fact` + 4 个来源适配器（会议、邮件、云效工作项、大事儿）+ 每 30 分钟汇入；✅ `/internal/project-context/run` 无人在场跑一轮；✅ `project_facts` / `save_daily_brief` 两个工具；✅ 晨报写入 + 07:30 企微分发；✅ `agent-loop/` 接入包（rules.json + shim）；⏳ 部署 autoloop、前端晨报卡 | 次日 07:30 成员收到「今天 3 件事」，每条能点回原始会议/工作项/邮件；夜里等待期间无 agent 进程常驻（autoloop `status` 可见） |
| **M1 制品与评审**（后端+前端已落地：V118 / AiArtifact / 工作台待评审卡） | `ai_artifact` + 需求/方案类制品；工作台评审队列；采纳回流 | 一件「要出方案」的事，夜间产出方案草稿，人只改 3 处内即可用；采纳率有数 |
| **M2 代码类夜间执行**（后端绑定接口与项目详情绑定界面已就绪；夜间出补丁链路待做） | `project_git_repo` 绑定；dsh 通道；SQL 补丁/修复说明 + 分支隔离 | 一个缺陷夜间出分支 + 补丁 + 修复说明；主分支零改动；人 review 后由人合并 |
| **M3 团队效能视图** | 主管视角：谁卡住、谁超载、哪些项目事实缺投递 | 主管每天一份团队版；投递率可度量 |

## 八、技术栈重构代价（坦白列出来）

| 现状 | 变化 | 影响面 |
|---|---|---|
| `AiAgentController.java:204` SSE 驱动的 run | 保留给「人对话」，**不再承担自动化** | 控制器职责收窄，抽 `SidecarRunClient` |
| `AiAgentSessionService` 会话持久化 | 保留；夜间任务由 autoloop 持有会话续接 | 双会话源要注意：**同一任务必须固定在 autoloop 一侧续跑**，避免两套历史 |
| `AiProactiveService` 6 条规则 | **迁出**成 autoloop 规则 + 事实层查询，避免两套并存 | `ai_proactive_suggestion` 保留展示层，数据源改为任务结果 |
| `AiProactivePushScheduler` 08:50 推送 | 与 07:30 晨报合并 | 通知口径统一 |
| 侧车 0.84.4 | 升级 1.5.0 + 修 §2.3 三个假数 | `run.ts` 两处函数 |
| 新增运行时 | Node 24 + `~/.agent-autoloop` + SQLite | 运维新增一个进程、一份备份目录 |

## 九、风险

| 风险 | 处置 |
|---|---|
| 成员不投递 → 事实层空 → 夜间产出是编的 | **无证据不派活**；先把投递做成「投了就立刻有回报」；M3 度量投递率 |
| 跨公司可见性泄漏 | `visibility` 字段 + 项目授权 + 派活时校验；MCP 工具按调用者身份过滤（沿用现有行级过滤） |
| 夜间产出低质、人审负担反而变重 | 制品必须指出「依据哪几条事实」；连续两次被驳回的规则自动降频/停用 |
| 成本失控 | 任务级预算 + `max_hop`（建议 3）+ 每项目每晚任务数上限；先只跑皇家项目一个 |
| 两套状态机撕裂 | 合拍只存「事实/制品/晨报」，不存任务状态；任务状态唯一来源 = autoloop |

## 十、等你确认的三件事

1. **第一个试点项目**用皇家项目吗？（M0 只跑它一个）
2. **公司 Git 的协议**：HTTPS + Token，还是 SSH key？dsh 在服务器上跑需要能 clone（决定 `project_git_repo` 的凭据形态）。
3. **晨报的默认收件范围**：项目组全员，还是先只发项目经理 + 研发负责人？
