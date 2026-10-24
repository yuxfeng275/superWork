# AI 能力与开关说明

> 更新时间：2026-10-22 · 维护者：master agent

## 一、模块与入口

| 模块 | 入口 | 依赖 | 说明 |
|---|---|---|---|
| AI 助手 | 侧栏「AI 助手」`/ai-assistant` | ai-sidecar 容器 + 模型管理 + 连接器 | 自研 agent loop：Spring Boot 管会话/附件/工具执行，Node 侧车跑 pi-agent-core，SSE 流式回传 text/thinking/工具事件 |
| 模型管理 | `系统管理 → 系统配置 → 模型管理` `/system/models` | 无 | 模型注册表：地址/Key/排序，用途勾选「助手可用 / 摘要使用 / 决策使用 / 默认」 |
| 周报生成 | 周报中心 `/weekly-report` | 摘要模型 | 自动采集事实 + 固定模版输出 + 模版自检重试；KPI 段由利润表计算后覆盖 |
| 会议纪要 | 会议 `/meetings` | asr-worker + 摘要模型 | 转写 → LLM 纪要（summary/decisions/risks/todos，带 segmentRef 溯源）→ DRAFT 待办 |
| 邮件 AI | 邮件管理 `/emails` | 摘要模型 | 分组（LLM）、单封「AI 解读」、每日摘要（LLM 失败回落规则生成） |
| Jev 决策层 | 无独立入口 | typesafe 连接器 + 决策模型 | 意图分类收窄工具集 + 写操作门禁（回信/转任务/发企微等） |

## 二、摘要模型兜底（2026-10-22 起）

`DigestLlmClient` 是周报与会议共用的 LLM 入口：按「模型管理」排序依次尝试勾选**摘要使用**的模型，
首个候选返回 402（余额不足）/401（鉴权失败）/超时/非 JSON/**JSON 缺必填字段**时自动换下一个；全部失败才报错，
错误信息按候选逐条汇总，例如：

```
所有摘要模型均失败：deepseek/deepseek-flash HTTP 402（{"error":{"message":"Insufficient Balance"}}）；bai/deepseek-v4.1-flash HTTP 500（…）
```

- 周报的 `generation_provider` / `generation_model` 记录**实际成功**使用的模型。
- 候选为空时提示「请在模型管理勾选摘要使用」，不再依赖 email-integration 旧配置里的 DeepSeek 参数。
- 至少配两个摘要模型并在模型管理里排好顺序，才能真正兜底。
- **需要出网代理的提供方**（如 `bai`）按同名连接器的 `extra.proxy` 走 HTTP CONNECT 前代；
  客户端按 proxy 缓存连接池，失败信息会标注「（经代理 http://mihomo:7890）」。

### 241 网络可达性实测（2026-10-23，容器内直连）

| 提供方 | 地址 | 结果 |
|---|---|---|
| DeepSeek | `api.deepseek.com` | 直连可达，158ms（401 = 需鉴权） |
| 智谱 GLM | `open.bigmodel.cn` | 直连可达，113ms（401 = 需鉴权） |
| b.ai 中转 | `api.b.ai` | **直连超时**（8s 无响应），必须走代理 |
| b.ai 中转 | 经 `mihomo:7890` | 可达（返回 401），代理配置 `{"proxy":"http://mihomo:7890"}` 有效 |

结论：241 上做兜底优先选**直连可达**的提供方；选 b.ai 这类必须确认连接器 `extra.proxy`
已配且 mihomo 健康，否则它的候选一定失败。

### 当前兜底链（2026-10-23 起，线上生效）

| 顺序 | 候选 | 接入方式 | 摘要用途 | 助手默认 |
|---|---|---|---|---|
| 1 | `bai / deepseek-v4.1-flash` | 经 `mihomo:7890` 代理 | ✅ | ✅ |
| 2 | `deepseek / deepseek-flash` | 直连 `api.deepseek.com` | ✅ | — |

2026-10-23 起 **bai 优先**（sort_order 10，deepseek 降到 100）；`ai.digest.timeout-seconds`
默认 180s（经代理的候选较慢，原 120s 余量太薄）。实测 bai 主路径生成 2026-10-19 周报
成功（`generation_provider=bai`、无错误、810 字）。

实测：把 deepseek 摘要临时关掉后重新生成 2026-10-19 周报，35 秒完成、状态 DRAFT、
`generation_provider=bai`、`generation_error` 为空、正文 752 字 —— 说明 b.ai 经代理
不仅连通，也能正常吃 `response_format: json_object` 的长文本生成，是可用备胎。
（该验证为一次性临时切换，已恢复 deepseek 摘要开启。）



## 三、AI 助手用量统计

- 侧车 `run_end` 返回的 assistant 消息带 `usage`（input/output/cacheRead/cacheWrite/totalTokens/cost），
  后端落 `ai_agent_usage` 表（`run_id` 唯一，重复事件不重复计）。
- `GET /api/ai-agent/usage` 返回当前用户今日 / **近 30 天（滚动窗口，不是自然月）** / 累计 的次数、token 与估算成本；
  助手页头部文案为「近 30 天 N 次 · X tokens」（字段名仍是 monthRuns/monthTokens）。
- 成本为侧车按模型定价估算的美元值，仅作趋势参考。

## 三之二、周报生成失败即时告警

`generate()` 入 catch 分支后立刻向 `weekly.report-notify-wecom-user` 推企微：
包含周起始日、失败原因（截断 300 字）与 `/weekly-report` 链接，避免周五 17:00 挂了
等到第二天早上才被发现；未配置接收人则静默跳过，告警失败不影响错误落库。

> 前置条件：241 需设置 `WEEKLY_NOTIFY_WECOM_USER`（企微 userid）与 `APP_PUBLIC_BASE_URL`，
> 否则本告警与周报成功通知都不会发出。

## 四、Jev（TypeSafe System One）决策层

代码已就绪（`JevDecisionService`，未配置或调用失败一律 fail-open，不阻断对话）。开启步骤：

1. 连接器管理里配置 `typesafe` 连接器（地址 + 凭据），使其状态为 READY。
2. 模型管理新增 `typesafe / jev-latest` 模型，勾选「启用 + 决策使用」。
3. 观察 `/api/ai-agent` 会话流里的 `jev_decision` 事件：命中意图时会收窄工具集，
   写操作（reply_my_email / convert_email_item_to_task / weekly_report_input / wecom_send_message /
   wecom_create_todo / wecom_finish_todo）需置信度 ≥ 0.70（noul ≥ 0.55）才放行。

## 五、已知边界

- 附件只做文本摘要注入（txt/md/csv/json/xml/log 等 ≤8000 字），不做图片/PDF/Office 解析。
- 无向量检索（无 embedding 索引），语雀与邮件均为关键词/接口直查。
- 助手会话不可导出；用量表只统计成功落库的 run。
