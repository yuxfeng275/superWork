# M0 端到端冒烟（agent-loop/smoke.sh）

验证「夜间产出 → 次日推送」这条链路上**合拍侧自己负责的四段**是否打通：
事实汇入 → 事实读取 → 写晨报 → 读晨报。不需要模型 key，不依赖 agent-autoloop。

```bash
chmod +x agent-loop/smoke.sh
HUJI_BASE_URL=http://127.0.0.1:8081 \
HUJI_TOKEN="$PROJECT_CONTEXT_TOKEN" \
PROJECT_ID=1 \
TEST_USER_ID=2 \
HUJI_JWT="<可选：登录后的 access token>" \
bash agent-loop/smoke.sh
```

退出码：`0` 全部通过（或第 4 步按规则跳过）；`1` 某一步非 2xx 或业务码不是 200（会打印响应体）；`2` 缺环境变量。

## 四步分别验证什么

| 步 | 请求 | 验证点 | 预期输出 |
|---|---|---|---|
| ① | `POST /internal/project-context/{PROJECT_ID}/ingest?hours=24` | 令牌鉴权 + 事实层汇入链路（会议/邮件/云效工作项/大事儿 → `project_fact`）真的能跑；`ProjectFactService.ingestProject` 不抛异常 | HTTP 200，`{"code":200,"data":<changed>}`；`changed=0` 也算通过（这段时间没变动） |
| ② | `GET /internal/project-context/{PROJECT_ID}/facts?limit=5` | 夜间 Agent 取证据的读路径 + `limit` 生效 + 返回结构是数组 | HTTP 200，`data` 为数组，可能为空；脚本打印实际条数 |
| ③ | `POST /internal/project-context/briefs` `{userId,projectId,briefDate,content,sourceTask}` | 晨报写入/覆盖（`(user_id, brief_date, project_id)` 唯一）+ 日期口径 + `content` 能存下夜间实际写的 JSON（`{"items":[...]}`） | HTTP 200，`data` 为晨报行 id；同一 `briefDate` 重跑只覆盖不新增 |
| ④ | `GET /api/ai-briefs?date=<今天>` | 成员侧读取（登录态）能读到 ③ 写的那条 —— 即前端/推送看到的同一份数据 | HTTP 200，`data` 数组里含 `content` 为测试标记的那条 |

> ② 的条数是脚本用 `"sourceType"` 计数估的；`data:[]` 也算通过——夜里有变动才会有事实。

> ③ 写入的是 **今天** 的晨报，正好是 07:30 推送要读的那一行（`brief_date=今天 and delivered_at is null`）。
> 白天在共享环境跑之前，先 `PROJECT_BRIEF_PUSH_ENABLED=false` 重启后端，或者跑完立即删行，
> 否则测试晨报可能在次日 07:30 被推到真人企微。

## 第 4 步需要登录态

`/api/ai-briefs` 走 `@RequestAttribute("userId")`（`JwtAuthenticationFilter` 注入），内部 `X-Sidecar-Token` 进不来。三种做法：

1. 给 `HUJI_JWT`（推荐）：先 `POST /api/auth/login` 拿 `data.token`，脚本自动带上 `Authorization: Bearer`。
2. 不给 `HUJI_JWT`：脚本打印 `SKIP ④` 并退出 0，同时给出上面那条 login 命令和一条可执行的 SQL（内部读法）。
3. 直接查库：`SELECT id,brief_date,project_id,content FROM ai_brief WHERE user_id=... AND brief_date='...';`

## 日期口径（跑通这条链的关键）

- 夜间规则（22:00）调 `save_daily_brief` 时必须传 `briefDate=明天`——否则 `brief_date=当天`，次日 07:30 的 `ProjectBriefPushScheduler` 按 `brief_date=今天` 精确匹配，永远空集。
- 晨报补发规则改到 **07:00**，写 `briefDate=今天`，与 07:30 推送同一分钟抢跑的问题一并消掉。
- 07:30 推送读 `brief_date=今天`；`delivered_at` 非空的不会重复推，所以本地可以把 `project.brief.push-enabled=false` 关掉再冒烟。

## 预期失败与不属于缺陷的现象

- `POST /internal/project-context/run` 在**没有可用助手模型（模型管理里未配置/未勾选）时返回 400**，`message` 形如「模型管理里没有可用的助手模型」。这是预期行为：run 走真实模型，冒烟脚本刻意不调它。想验 run 需要先在「系统配置 / 模型管理」里配好模型并把 `userId` 指向试点成员。
- `HUJI_TOKEN` 为空时后端默认不校验令牌（开发口径），脚本会打警告但继续；生产必须设 `project-context.token` / `PROJECT_CONTEXT_TOKEN`。
- ③ 会写一条测试晨报，跑完记得清：`DELETE FROM ai_brief WHERE id=<返回的 id>;`（脚本最后一行也打印了这条）。

## 本机环境要求

- **后端编译/测试前必须把 `JAVA_HOME` 指向 JDK 17**：

  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 17)
  ```

  默认若仍指向 JDK 8，`mvn` 会直接报 `Bad CPU type in executable`。

- 已实测通过的验证命令：

  ```bash
  cd backend && mvn -o -q -DskipTests compile
  cd backend && mvn -o -Dtest='ProjectFactServiceTest,ProjectGitRepoServiceTest,MigrationV117SanityTest,JevDecisionServiceTest,AiAgent*Test' test   # 51 个用例全绿
  cd frontend-pro && npx tsc --noEmit    # 0 错误
  node --check agent-loop/sidecar-agent.mjs
  bash -n agent-loop/run-huji-agent.sh
  ```
