# 夜间批端到端演练报告（2026-11-21）

> 环境：241 生产（`superwork-bu-*`，backend 镜像由 master 现场构建）
> 目的：验证「触发器只发开始 → 合拍编排 → 执行器干活 → 制品与晨报 → 次日审核」这条链路真的能跑。

## 一、部署与迁移

| 项 | 结果 |
|---|---|
| 后端 | `mvn clean package`（241 上，跳过测试）→ 96MB jar → 镜像重建 → healthy |
| 前端 | `max build` → dist rsync → 镜像重建 → healthy |
| 迁移 | V149（ai_file）、V150（ai_run/ai_run_node/ai_invocation）、V151（ai_gate）全部 `success=1` |
| 配置 | `ai.file.store=minio`（prod）；修复 `minio.access-key` 自引用占位符；修复 `ai:` 顶层键重复 |

## 二、统一空间（成功标准 2）

| 步骤 | 证据 |
|---|---|
| 上传 `space-test.md`（122B） | `ai_file.id=1`，`sha256=f93d613e…`，`object_key=1/f9/<sha>.md` |
| 原文落 MinIO | 实体文件：`docker/minio/data/bu-management/1/f9/f93d….md` |
| 生成事实 | `project_fact.id=103`，`source_type=FILE_UPLOAD`，`source_ref=file:1`，digest 含文件名+大小+正文开头 |
| 同内容经 EMAIL 再投 | 返回 `fileId=1, factId=103, deduped=true`；库中 `source_ref=file:1` 的事实**仅 1 条** |

## 三、编排与执行（成功标准 3、6）

| 轮次 | 触发 | 结果 |
|---|---|---|
| run 1（project 4, user 9） | 内部 token 端点 | **暴露出两个真问题**：① `run_no` 插入后才回填，违反 NOT NULL；② 模型因无项目权限而放弃产出，节点却记 SUCCEEDED |
| run 2（project 1, user 9） | 同上 | SUCCEEDED：facts(28 条) → draft → brief；产出 `ai_artifact#39`（PLAN/IN_REVIEW，evidence `[80,54,57,56,55,66,70,67,69,65]`）；晨报 `ai_brief#21`（2026-11-22，3 条） |
| run 3（project 1，bizDate 11-22） | 同上 | SUCCEEDED；**首次写入真实 `ai_invocation`** 6 条：`project_facts`×2、`save_artifact`、`query_my_tasks`、`query_my_requirements`、`save_daily_brief`，全部 SUCCEEDED 且各带 `logical_action_key` |
| 触发器链路 | `agent-autoloop run 皇家项目夜间扫描` | 第 49 轮回执「夜间批已由合拍编排：NR-20261121-232140-8238（runId=2，状态=SUCCEEDED）」；规则已改为调用 `night-batch-trigger.sh`，提示词只剩一句话 |

失败注入（不依赖生产）：

| 崩溃窗口 | 测试 | 断言 |
|---|---|---|
| begin 之前 | `AiInvocationServiceTest.crashBeforeBeginLeavesNothingBehind` | 无记录、重试执行 1 次 |
| begin 之后、副作用之前 | `crashBetweenBeginAndSideEffectIsRecoveredByRecheck` | 标 UNKNOWN；同 attempt 不执行；核对未生效后新 attempt 执行 1 次 |
| 副作用之后、结果之前 | `crashAfterSideEffectDoesNotDoubleWrite` | 标 UNKNOWN；重放不执行；核对后记 REPLAYED，业务写入仍为 1 |

审批幂等：`AiGateServiceTest` 6 例——重复审批影响 0 行并报错、过期 attempt 被拒、打回只重置当前与 `rejectTo` 目标且不动上游制品。

## 四、运行面（成功标准 7）

| 检查 | 结果 |
|---|---|
| `GET /ai-runs`（130.80） | 200（页面），`/api/ai-runs` 401（需登录） |
| `GET /api/ai-runs/{id}` | 返回 run + 节点 + `unknownInvocations`（STARTED/UNKNOWN 计数） |
| 节点状态机 | UNKNOWN 用独立颜色「结果未知 · 可能已写入，请先核对再重跑」 |
| 测试 | 后端 `mvn test` 1061 通过；前端 `vitest` 176 通过 |

## 五、遗留问题（已记录，不掩盖）

1. **节点级闸门尚未接入 SOP**：`ai_gate` 服务/接口/测试已就绪，但夜间批目前直接跑完，人工介入靠制品评审队列。接入需要「审批后恢复 run」的回路。
2. **`PROJECT_CONTEXT_TOKEN` 在 241 为空**：内部端点目前无 token 即可访问（仅本机反代内网可达），上线前必须设置。
3. **`agent-autoloop` 的晨报补发规则仍是旧链路**（`run-huji-agent.sh`），待夜间批稳定后一并切换。
4. **5 个工作日指标**：从 2026-11-24（周一）起采集，用 `agent-loop/ai-night-metrics.sh` 每日取数。
