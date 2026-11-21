# 合拍 AI 目标架构（夜间接管）

> 目标：白天团队在合拍里沟通、投递文件、推进业务；下班后信息与文件沉淀为事实；夜里 Agent 在人工闸门下干活；次日团队审核继续推进。
> 本文只定「谁在哪一层、谁不许越界」，不写实现细节。流程见 [02-flow.md](02-flow.md)，功能与表见 [03-functions.md](03-functions.md)。

## 一、三条不变量（违反即架构错）

1. **编排主控只能是合拍。** 多步流程（候选 → 制品 → 晨报 → 审核 → 回写）的状态机、闸门、租约、幂等，全部在合拍（Java/MySQL）里。agent-autoloop 只做「什么时候干活」，不做「活怎么算干完」。
2. **写权限只在合拍拦。** 执行器（dsh / sidecar / CLI）的 sandbox 是它自己的事；业务红线（能不能发邮件、能不能改状态）只由合拍的 `ai_invocation` + Jev 门禁判定。
3. **执行器可替换。** 换 dsh 还是 sidecar，不改业务代码、不改表结构；只换插槽实现。

## 二、分层

```
L5  人的决策面      工作台 · 待评审 · 晨报 · 企微卡片 · 拍板
     ▲
L4  交付与证据      ai_artifact（制品+attempt+证据链）· ai_brief
     ▲
L3  执行器插槽      ACP→dsh ｜ HTTP→ai-sidecar ｜ CLI→codex/claude ｜ script
     ▲                （能力上限由插槽决定，业务语义在上层）
L2  编排内核 ★新建★  ai_run / ai_run_node / ai_invocation / ai_gate / lease
     ▲                 必须与业务事务同源
L1  触发层          事件（会议/邮件/工作项/状态变化）· 定时 · 人工发起
     ▲                 agent-autoloop 或 @Scheduled，只留一种语义
L0  事实与空间      project_fact（只追加·唯一真相）+ ai_file（统一空间原文）
```

| 层 | 现在有什么 | 缺什么 |
|---|---|---|
| L0 | `project_fact`（V117，dedupe_hash 唯一）、`ProjectFactIngestScheduler`（每 30 分钟从会议/邮件/工作项/大事儿汇入）、`ProjectFactStorage`（本地盘 + sha256 寻址 + tmp/part 原子改名） | 面向「团队文件」的空间与权限；对象存储未接到 MinIO |
| L1 | agent-autoloop（systemd user 单元，v1.9.5）+ 合拍 12 处 `@Scheduled` | 触发与编排的边界未划清：规则里直接写流程 |
| L2 | 无 | **全部** |
| L3 | `ai-sidecar`（`POST /v1/runs`，SSE；600s/200 条消息/工具回调 120s） | dsh 未接；`command` 走 20 行 shim |
| L4 | `ai_artifact`（五态 + evidence 必填）、`ai_brief`（一人一天一份） | attempt / 证据哈希 / 下游引用；gate 决策未入制品 |
| L5 | 工作台待评审卡、晨报卡、企微推送 | 运行面（run/节点/待我审批/失败诊断） |

## 三、执行器插槽（L3 的唯一契约）

```java
public interface AgentExecutor {
    String kind();                                   // "sidecar" | "dsh-acp" | "cli"
    boolean supports(ExecCapability cap);            // RESUME / STDIN / STRUCTURED / FILES / CANCEL
    ExecTicket start(ExecRequest req, ExecSink sink); // 异步启动，立刻返回句柄
    void cancel(ExecTicket ticket);
}

public record ExecRequest(
        String runId, String nodeId, int attempt,
        Long userId, Long projectId,
        String systemPrompt,      // 由规则给出，禁止在服务层硬编码产出口径
        String prompt,
        List<ToolDef> tools,      // 由 CapabilityRegistry 按 userId 冻结后传入
        String resumeSessionId,   // 有则续跑（dsh: session/load，sidecar: messages 回放）
        Map<String, String> env) {}

public interface ExecSink {                       // 执行器只能通过这些事件讲话
    void onSession(String executorSessionId);
    void onText(String delta);
    void onThinking(String delta);
    void onToolCall(String callId, String tool, String argsJson);
    void onToolResult(String callId, String resultJson, boolean isError);
    void onFinish(ExecOutcome outcome);           // OK / NEEDS_INPUT / FAILED / CANCELLED / UNKNOWN
}
```

实现约定：

| 插槽 | 现状 | 接入要点 |
|---|---|---|
| `sidecar` | `/v1/runs` 已有 SSE 事件（`run_start`/`message_delta`/`tool_execution_*`/`run_end`），工具回调走 `/internal/ai-agent/tools` | 直接映射到 `ExecSink`；`run_end.newMessages` 落会话 |
| `dsh-acp` | 241 已装 dsh（`~/.dsh`，headless profile），有 ACP server；agent-autoloop v1.9.5 已验证 `use:"acp"` | 走 stdio ACP：`session/load` → prompt → 语义更新 → `cancel`；权限按 `read/edit/full` 一次性授予 |
| `cli` | `nightly.sh` / `run-huji-agent.sh` 现状 | 仅保留给脚本类节点，不作为流程主控 |

## 四、对象存储（L0 原文放哪）

现状是**本地盘**：`ProjectFactStorage` 与 `AiAgentAttachmentStorage` 都以 `Path` 为根，`sha256` 寻址，`.part` → 原子改名。`minio.*` 配置存在（`bucket-name: bu-management`）但没有接进这两处。

设计：抽一层 `ObjectStore`，`put/open/delete/presign`，两套实现——`LocalObjectStore`（现状，dev 与单机）与 `MinioObjectStore`（prod）。理由：不割裂现有代码，同时满足「文件原文进 MinIO」的目标。

```
L0 统一空间：ai_file（元数据，MySQL）+ ObjectStore（原文，MinIO/本地盘）
   ai_file.object_key = <projectId>/<sha256[0:2]>/<sha256>.<ext>
   相同 sha256 不重复存；project_fact.source_ref = "file:<ai_file.id>"
```

## 五、部署拓扑（241 现状，不改）

9 个容器：`nginx(:18080 入口)` → `frontend-pro` / `backend(:8081)`；`ai-sidecar(:8787)`；`mysql`；`redis`；`minio(:19000)`；`asr-worker`（会议转写，内网 8790）；`mihomo`（出口代理）。

新增只需要两件事：`ObjectStore` 接 MinIO；dsh 以 ACP 子进程方式由 backend 或独立 runner 拉起（不新增长期容器）。

## 六、与四个开源项目的关系

| 项目 | 拿什么 | 不拿什么 |
|---|---|---|
| **dsh（DeepSeek Harness）** | 三层上下文预算（工具输出 50KB 落盘、历史工具文本 8,192 码点裁剪、压力 80% 才摘要）、副作用前 flush 与「结果未知」语义、per-call 审批、ACP 会话续跑 | Cordis 插件运行时、动态执行模型生成 JS、跨平台 sandbox、Zstd JSONL 会话日志 |
| **StaffDeck** | 产品语义：员工/技能/知识版本化；SOP 版本化图 + validate/publish；`harness_turns/runs/invocations` 的幂等分层；知识三层证据链 | 它的 Python 运行时与固定产出的 harness v3 流程 |
| **LoopForge** | 数据模型：Run / NodeRun / Attempt / Gate / 租约 epoch / rejectTo 返工 / 决策制品 / 诊断包 | 不部署它的 Go 平台；不用它做第二套编排 |
| **agent-autoloop** | 触发与问责：规则、事件、卡片、回执、确定性状态机 | 不让它决定多步流程；不把业务写权限交给它 |

## 七、禁止项（写进 code review 检查表）

- 在 service 里硬编码「本轮产出是什么」（当前 `AutomationRunService` 就违反了这条，见 03 §五）。
- 让 `command` shim 承担流程判断（第二步做什么）。
- 在执行器侧做业务权限判断，或在合拍之外改业务状态。
- 夜间任何对外发送、任何把状态改成「完成」。
- 一个新的触发器/编排框架（不允许第五套）。

## 八、验收这个架构对不对（反向校验）

- 把 AI 全关掉，合拍业务照常运转；打开 AI，同一批事更快、每条结论可点回证据。
- 同一任务把执行器从 sidecar 换成 dsh，业务代码零改动。
- 杀掉 backend 进程再起来，夜间任务不重复写、不静默丢单。
