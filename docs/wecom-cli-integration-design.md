# 企微连接器接入 wecom-cli（机器人通道）调研与集成设计

> 调研对象：<https://github.com/WecomTeam/wecom-cli>（官方 CLI，MIT，v1.3.0 / 2026-09-16 构建）
> 结论均经**源码核对 + 生产服务器（241）实机验证**，非文档转述；实测记录见「附录 A」。

## 一、结论摘要

1. **它是"机器人通道"，不是现有连接器的替代品。** 现有 `wecom` 连接器走**自建应用通道**（corpId + agentId + secret → 应用消息推送 / 通讯录同步 / OAuth 免登 / 审批只读）；wecom-cli 走**智能机器人通道**（Bot ID + Secret 或扫码绑定 → 以机器人/用户视角操作待办、会议、文档、表格、微盘、邮件、日程、通讯录）。**两者授权域不同、能力互补，应并存于同一张「企业微信」连接器卡片下。**
2. **可以内嵌后端，不需要 Node、不需要新容器。** npm 包 `@wecom/cli-linux-x64` 里的 `bin/wecom-cli` 是 **static-pie 全静态 ELF（11.4 MB，无 libc 依赖）**，Node 只是启动器。实测在 241（x86_64）直接执行成功 → 后端镜像 `COPY` 一行即可（`Dockerfile.runtime`）。
3. **凭据可移植，无需在服务器重新扫码。** 实测把本机 `~/.config/wecom/`（`credentials.enc` + `.encryption_key`）复制到 241，`auth show` 直接 `authorized`（同一 Bot `aibCQMhZJcCwvUP-ao7CJK-XfXFApdiEv9J`）；access token 过期由 CLI 静默续期（`853004` → 自动刷新），只要 Bot 凭证在，长期可用。
4. **真正的门槛是"品类授权"，且会过期。** 机器人对每个业务品类需由**创建者**在企微侧单独授权，授权有有效期。实测 241 出口 IP `59.46.222.98` 调用结果：待办 `850003 authorization expired`、会议/通讯录 `850002 no authorization`、文档 `851008 partial no authorization`。**这不是网络/IP 问题（请求已到达并返回业务错误），而是授权状态问题。**
5. **因此集成方案的核心不是"怎么调 CLI"，而是"品类授权状态可视化 + 一键续期引导"**，否则 AI 工具会在运行时静默失败，用户体验极差。CLI 错误体自带 `help_message`（含续期链接），官方要求**逐字原样展示**——直接透传到前端即可闭环。

## 二、wecom-cli 关键事实（源码/实测核实）

### 2.1 命令模型与能力面

| 项 | 事实 |
| --- | --- |
| 命令形态 | `wecom-cli <service> [resource...] <method> [flags]`；`auth` 为内建扩展命令；`schema`/`cache` 为隐藏调试命令 |
| 服务目录 | **在线 discovery 下发**（`schema list`、缓存 TTL 60s），离线不可用；1.3.0 曾重构接口名（旧扁平名 `contact get_userlist` → 新路径名 `contact users search`），**必须锁版本** |
| 实测服务清单（v1.3.0） | `auth` `calendar` `chat` `contact` `disk` `doc` `mail` `media` `message` `meeting` `sheet` `smartpage` `smartsheet` `todo` |
| 输出 | stdout = compact JSON；stderr = 日志/提示（不污染 stdout）；`--page-count` 自动分页输出 NDJSON |
| 退出码 | `0` 成功（含 `--help`）；`1` 运行时错误；`2` 用法错误 |
| 错误格式 | `{"error":{"type","code","message"}}`，CLI 段 `893000–893299`，`893999` 兜底；**业务 errcode 原样透传**（如 `850003`） |
| 调试/集成 flag | `--dry-run`（只校验不发请求）、`--json '<JSON>'`、`--set path=val`、`--output/-o <file>`、`--output-dir <dir>` |

### 2.2 授权模型（集成关键）

| 项 | 事实 |
| --- | --- |
| 授权命令 | `wecom-cli auth init`（交互选择）· `--noninteractive`（直连扫码，非交互环境适用）· `--manual`（手输 Bot ID+Secret，**需 TTY**）· **隐藏参数 `--bot-id` + `--secret`（成对，可无 TTY 直连）** |
| 授权状态 | `wecom-cli auth show`（Status + Bot ID）· `auth show --status` → 单行 `authorized`/`unauthorized` |
| 扫码流程 | 终端渲染二维码 + 可选 `--output-qrcode <PNG>`（与 `--manual`/`--bot-id` 互斥）· `--no-browser` 不弹浏览器 · **扫码超时 5 分钟** |
| 凭据落盘 | `<config_dir>/credentials.enc`（AES-256-GCM，0600；bot 信息与 token 同文件） |
| 密钥来源 | 系统 keyring，无 keyring 时回退 `<config_dir>/.encryption_key`（0600）→ **容器/无桌面环境天然可用** |
| 配置目录 | 默认 `~/.config/wecom`，可用 `WECOM_CLI_CONFIG_DIR` 覆盖（相对值按进程 cwd 解析） |
| 鉴权端点 | `https://qyapi.weixin.qq.com/cgi-bin/aibot/cli/get_cli_config`（Bot 凭证换 access token） |
| token 续期 | 收到 `853004 token expired` 时**静默自动刷新**（`crates/wecom-cli/src/transport/backend.rs`），无需人工重授权 |
| 品类授权 | **按品类单独授权、有有效期**：`850002` 未授权 / `850003` 已过期 / `851008` 部分未授权；错误体含 `help_message`（续期链接，官方要求逐字展示） |

无 TTY 直连分支（源码 `crates/wecom-cli/src/cmd/auth.rs`）：

```rust
if let (Some(botid), Some(secret)) = (args.bot_id, args.secret)
    && !std::io::stderr().is_terminal()
{
    return init_with_bot(run, auth::Bot::new(botid, secret), auth::BindSource::Interactive).await;
}
```

→ **Java `ProcessBuilder` 场景（stderr 非 TTY）可直接用 Bot 凭证完成无人值守授权**。

### 2.3 运行时约束（1.3.0 起）

- **文件沙箱**：读写仅限「进程 cwd + 系统临时目录」；**配置目录一律拒绝**（含任意基座下的 `**/.config/wecom`）；`--output`/`--output-dir` 目标必须落在 cwd 或 tmp 内；deny 清单覆盖 `.ssh`/`.aws`/`.kube`/`.env`/`.git`/`*.pem`/`*.key`/`id_rsa` 等。
  → 部署时须给 CLI 一个**专用工作目录**（下载落盘处），并让 `WECOM_CLI_CONFIG_DIR` 指向**绝对路径**的配置目录。
- **遥测**：编译特性 `call-chain` **默认开启**（npm 预编译版），调用时回传「调用方 Agent 进程名 + 调用链路」到企微云端；关闭需从源码构建（`cargo build -p wecom-cli --no-default-features`）。属于官方遥测，仅进程名，可接受（如需关闭则失去 npm 预编译便利）。
- **环境变量**：`WECOM_CLI_CONFIG_DIR`、`WECOM_CLI_ADDITIONAL_HEADERS(_*)`、`WECOM_CLI_LOG_LEVEL`、`WECOM_CLI_LOG_DIR`；`config.json` 仅支持 `headers`；**access token 不允许经配置文件提供**。

### 2.4 官方 Agent Skills（可直接借用的资产）

仓库内置 14 个 Agent Skills（`skills/wecomcli-*`，`npx skills add WecomTeam/wecom-cli -g` 安装）：`shared` + `contact/calendar/meeting/todo/email/disk/media/message/doc/doc-manage/sheet/smartsheet/smartpage`。

- `wecomcli-shared` 规定：执行前检查 CLI 版本 ≥ `1.2.1` + 授权状态；**未授权时不得猜测状态**。
- **通用输出约束（对 AI 工具设计直接可用）**：最终回复**禁止出现 `userid`/`chat_id`/`docid`/`mail_id`/`cursor` 等 ID 类字段**，必须用可读名称（姓名/部门/主题/时间/路径）；需要用户选择时用「序号 + 可读信息」构造候选，禁止让用户凭 ID 辨认。

## 三、集成架构

### 3.1 双通道边界（放进同一张「企业微信」连接器卡片）

| 维度 | 应用通道（现有 `WeComClient`） | 机器人通道（新增 `WeComCliClient`） |
| --- | --- | --- |
| 凭证 | corpId + agentId + **应用 Secret** | **Bot ID + Bot Secret**（或扫码绑定） |
| 授权方式 | 企微管理后台配置，无人工 | 一次性授权（凭证/扫码），token 自动续期；**品类授权需创建者维护且会过期** |
| 典型用途 | 应用消息推送、通讯录同步、OAuth 免登、审批只读 | 待办读写、会议（含**智能纪要 + 转写原文**）、文档/表格读写、微盘、邮件只读、日程、通讯录搜索、会话消息 |
| 视角 | 企业/应用视角 | **机器人视角**（`todo list` = 机器人为创建人、对话人为参与人） |
| 现状 | 已接入（通知推送）；连接器 `wecom` 当前**未配置**（corpId/agentId/secret 空） | 本方案新增 |

**边界原则**：系统级触达（给谁推通知）走应用通道；用户级业务数据读写走机器人通道。二者不互相替代，凭证字段、加密存储、探活逻辑全部分开。

### 3.2 部署形态（无 Node、无新容器）

```
backend 镜像（eclipse-temurin:17-jre）
├── /app/wecom-cli/wecom-cli        ← COPY 自 @wecom/cli-linux-x64（static-pie，chmod +x）
├── 卷 wecom-cli-data → /data/wecom-cli
│     ├── credentials.enc            ← CLI 自管（AES-256-GCM）
│     ├── .encryption_key            ← 无 keyring 回退
│     └── workspace/                 ← ProcessBuilder 的 cwd（下载落盘，沙箱允许范围）
└── env: WECOM_CLI_CONFIG_DIR=/data/wecom-cli
```

- 镜像构建：`ARG WECOM_CLI_VERSION=1.3.0`，从 npm registry 取 `@wecom/cli-linux-x64@${WECOM_CLI_VERSION}` 解包 COPY（或构建期 `npm pack`）；**版本随镜像锁定**，升级走发布流程。
- 卷 `wecom-cli-data` 保证容器重建不丢授权；凭据文件 0600 + 卷仅后端可读。
- 首次授权可选：① 连接器填 Bot 凭证后由后端 `auth init --bot-id --secret` 自动完成（推荐）；② 把已授权工作站的 `credentials.enc` + `.encryption_key` 灌入卷（实测可行，用于迁移/应急）；③ 扫码（见 3.4）。

### 3.3 后端组件（映射到现有代码）

| 组件 | 落点 | 职责 |
| --- | --- | --- |
| `WeComCliClient`（新增，`integration/`） | 仿 `SeeyonOaWebChannel` 的定位 | `status()` / `init(botId, botSecret)` / `exec(service, path, args)`；统一超时、退出码、JSON 解析、`help_message` 提取、`850002/850003/851008` 归类为「品类未授权」 |
| `ConnectorRegistryService`（改） | `case CODE_WECOM` 校验/提示分支 | `extra.botId` 存 Bot ID；`botSecret` 走**新增加密列** `encrypted_bot_secret`（V83 迁移；不复用 `encrypted_token`，避免与应用 Secret 语义混淆）；提示语区分「应用通道未配置」/「机器人通道未授权」 |
| `ConnectorTestDispatcher.probeWeCom()`（改） | 现有探活 | 应用通道探活（现有）+ 机器人通道探活：`auth show --status` + 一次轻量只读调用 |
| `WeComCliController`（新增） | `/api/wecom-cli/**`，权限复用 `seeyon-oa:manage` 同级 | `GET /status`、`PUT /credentials`（Bot 凭证）、`POST /auth/qrcode` + `GET /auth/poll`（扫码兜底）、`GET /capabilities`（品类授权矩阵）、`POST /capabilities/{service}/probe` |
| `WeComCliToolService`（新增） | 与 `AiAgentToolService`/`GenericConnectorToolService` 并列 | 注册 AI 工具（见 3.5） |
| 前端连接器卡片（两套前端） | `frontend-pro/src/pages/connectors`、`frontend/src/views/ConnectorManageView.vue` | 「机器人通道」区块：状态徽标 + Bot ID + **品类授权矩阵（可用/过期/未授权）** + 授权/续期按钮 |

**存储决策**：`extra_config` 为明文 JSON，**禁止**存放 Bot Secret → 新增列 `encrypted_bot_secret`（复用 `EmailCredentialCipher` AES-256-GCM，与 OA 会话 Cookie 同一套加密）。`botId` 非敏感，存 `extra_config`（符合现有 `EXTRA_KEY_PATTERN` 校验）。

### 3.4 授权流程（两条路径）

**A. Bot 凭证（推荐，无人值守）**
```
连接器页填 Bot ID + Secret → PUT /api/wecom-cli/credentials
  → 后端 exec: auth init --bot-id <id> --secret <secret>   （非 TTY 直连分支）
  → 成功：credentials.enc 落卷；失败：透传 CLI 错误（含 errcode）
  → 立即 GET /capabilities 刷新品类授权矩阵
```
Bot ID/Secret 获取：企微「智能机器人」创建页（官方指引 `open.work.weixin.qq.com/help2/pc/cat?doc_id=21677`）。

**B. 扫码（拿不到 Secret 时的兜底，交互形态复刻 OA 授权弹窗）**
```
POST /api/wecom-cli/auth/qrcode
  → exec: auth init --noninteractive --no-browser --output-qrcode /data/wecom-cli/workspace/qr-<id>.png
  → 后端读 PNG → base64 回传前端弹窗展示（5 分钟倒计时）
  → 前端轮询 GET /api/wecom-cli/auth/poll → authorized 后关闭弹窗
```

### 3.5 AI 工具映射（首批建议）

| 工具名 | CLI | 说明 |
| --- | --- | --- |
| `wecom_search_contact` | `contact users search --keywords <name>` | 姓名/拼音/别名找人（**输出用姓名+部门，不外露 userid**） |
| `wecom_list_todos` / `wecom_create_todo` / `wecom_finish_todo` | `todo list/get/create/finish` | 机器人视角待办（与 OA 待办台互补：企微=个人待办，OA=审批流） |
| `wecom_list_meetings` / `wecom_meeting_detail` | `meeting list/get` | **`meeting get` 返回智能纪要地址 + 录制文件地址** |
| `wecom_search_doc` / `wecom_read_doc` | `doc search` / `doc contents get` | 企微文档检索与正文读取 |
| `wecom_send_message` | `message send`（先 `message aibot sessions list` 取会话） | 机器人推送到单聊/群（补应用通道推送） |
| `wecom_search_mail` / `wecom_read_mail` | `mail ...` | 只读邮件检索（与邮箱模块互补） |
| `wecom_search_disk` / `wecom_download_disk_file` | `disk ...` | 微盘检索与下载（下载落 `/data/wecom-cli/workspace`） |

工具实现约束（借官方 Skills）：
- **ID 类字段禁止外露**（`userid`/`chat_id`/`docid`/`cursor` 等只在内部流转，返回给模型/用户的必须是可读名称）。
- 工具描述中写入「前置检查」语义：调用失败且 errcode ∈ {850002,850003,851008} 时，把 CLI 的 `help_message`（含续期链接）**逐字**回给用户，并提示去连接器页查看品类授权矩阵。
- 参数用 CLI `--help`/`--schema` 核对后再固化（服务端 schema 在线下发，版本升级后需复核）。

### 3.6 会议模块联动（最高价值的一条）

`meeting get` 返回**智能纪要地址 + 录制文件地址**。链路：
```
AI 工具/定时任务 → meeting list/get → 纪要地址 & 录制文件地址
  → 抓取/下载 → 导入现有 meeting 模块（复用 asr-worker 流程；企微自带转写可直接作为"发言原文"落库）
  → 走既有"闪记式总结"流水线（章节/关键词/每人要点/待办转化）
```
价值：企微会议的转写由企微侧完成，**省掉自建 ASR 的算力与准确率成本**；把「企微开会 → superWork 自动出纪要与待办」打通。

## 四、分期落地

> **实施状态（2026-09-17）**：P0 / P1 / P2 已实现并上线 241；P3 待企微侧品类授权到位后再做（需先看到真实转写原文结构，避免盲写解析）。

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| **P0** 通道可用 | 镜像内嵌 CLI 二进制（`Dockerfile.runtime`，版本 + sha512 双校验）；`/data/wecom-cli` 数据卷；`WeComCliClient`（进程执行/解析/错误分类）；`extra.botId` + 新增加密列 `encrypted_bot_secret`（V83）；连接器校验与提示；`/api/wecom-cli/**`；`probeWeCom` 双通道探活（探活顺带完成机器人授权） | ✅ 已上线（实测：连接器保存 Bot 凭证 → `POST /api/wecom-cli/authorize` → `authorized:true`，Bot `aibCQMhZJcCwvUP-ao7CJK-XfXFApdiEv9J`） |
| **P1** 授权体验 | 扫码授权（二维码 + 5 分钟倒计时 + 3 秒轮询）；品类授权矩阵（8 品类体检、状态徽标、续期链接逐字透传）；两套前端连接器页「机器人通道」分区 | ✅ 已上线（实测矩阵：通讯录/会议/文档/微盘/邮件=未授权，待办/消息/日程=已过期，链接为企微真实续期地址） |
| **P2** AI 工具 | 11 个工具：通讯录搜索、待办列表/创建/完成、会议列表/详情/转写原文、文档搜索/读取、消息发送、邮件检索 | ✅ 已上线（实测：对话「查我的企业微信待办」→ 模型调用 `wecom_list_todos` → 工具返回企微续期引导 → 生成可操作答复） |
| **P3** 会议联动 | 企微会议纪要/转写导入 meeting 模块 + 总结流水线 | ⏸ 待品类授权（需真实 `original_data` 样例再写解析） |
| **P4**（可选） | 定时巡检品类授权到期并告警；CLI 版本升级流程 | ⏸ 未开始 |

**前置条件（业务侧动作）**：授权入口在连接器页品类矩阵的「点击这里」链接。注意两类不同路径（详见 4.2）：通讯录、待办、日程、消息等可由机器人创建者自助授权/续期；会议、文档、微盘、邮件提示「需向管理员申请使用」，须走企微内申请 + 管理员审批；消息「发送」能力（853006）须企业管理员开通。

### 4.2 真实数据回归（2026-09-18，品类授权后）

授权「待办 / 日程」后跑通真实链路，暴露并修复 4 个只有真实数据才会显现的问题：

| 问题 | 现象 | 修复 |
| --- | --- | --- |
| 待办 `creator` / `deadline` 实为**对象** | 创建人、截止时间恒为空 | 按 `creator.user_name` / `deadline.value` 取值；补 `extra_info`（提醒）与 `source` |
| 创建待办 `deadline` 需提交 `{type,value}` 对象 | `errcode=10003 'deadline' 类型不匹配` | 按值格式自动判 `date`/`datetime` 后提交对象 |
| 品类探针用固定时间窗 | 日程恒报 ERROR（企微限查当天 ±30 天） | 探针窗口改为按当天相对计算 |
| 消息探针用 sessions 口径 | 显示「可用」，实际发送返回 853006 | 探针改「发送」口径（不存在的会话），853006 → 未对企开放 |
| 企微注入 `extra_identity_context` | 会随写操作响应进入模型/对话上下文（官方明令禁止外泄） | `execOrThrow` 统一剥离 + 单测 |

**实测结论（真实数据）**：待办 读/创建/完成 ✓（测试数据已删除）；日程 85 条 ✓；AI 对话「我这周有什么日程 + 我的待办」→ 模型调用两个工具 → 输出带**时间冲突提示**的汇总 ✓。

**品类授权状态（关键运维事实）**：
- `待办`/`日程` = 可用；`消息推送` = 未对企业开放（`853006`，**需企业管理员**在企微侧开通，机器人创建者无法自助）；
- `通讯录` = 未授权（机器人创建者可自助授权）；
- `会议`/`文档`/`微盘`/`邮件` = 未授权，提示为「**需向管理员申请使用**」→ 走企微内申请+管理员审批，非自助；
- 机器人**主动发消息还需已有会话**：需先在企微与该机器人对话一次，`message aibot sessions list` 才有内容。

### 4.3 部署要点（易踩坑）

1. **数据卷属主**：容器内应用以 `spring`(uid 999) 运行，而 `docker compose` 首次创建 bind mount 目录时属主是 root → CLI 无法写入凭据。
   首次部署需执行一次：
   ```bash
   ssh server-241 'docker exec -u 0 superwork-bu-backend chown -R spring:spring /data/wecom-cli'
   ```
2. **镜像构建**：CLI 二进制从 npmmirror（回退 npmjs）下载，按架构（amd64/arm64）选择包并用 sha512 校验；校验失败即构建失败（不做静默降级）。
3. **CLI 沙箱（1.3.0 起）**：文件读写限「进程 cwd + 系统临时目录」，配置目录一律拒绝 → 后端固定以 `<config_dir>/workspace` 作为 cwd（下载落盘处）。
4. **凭据迁移**：可直接把已授权工作站的 `~/.config/wecom/`（`credentials.enc` + `.encryption_key`，两者缺一不可）灌入卷；实测跨机可用（token 过期由 CLI 静默续期）。

## 五、风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 品类授权过期/未授权（850002/850003/851008） | 工具运行时报错，AI 答"查不到" | 品类授权矩阵可视化 + `help_message` 逐字透传 + 续期入口（P1） |
| 服务端 schema 在线下发、接口名会变（1.3.0 已重构） | 升级后命令失配 | 镜像锁版本；升级前用 `--help`/`--schema` 复核工具参数；集成测试覆盖每个工具 |
| 离线环境 discovery 不可用 | CLI 完全不可用 | 明确依赖外网（现有部署已具备）；失败时给出明确提示 |
| 官方遥测回传调用方进程名 | 合规观感 | 知情接受（仅进程名+调用链）；如需关闭须自建（放弃 npm 预编译） |
| 文件沙箱限制（cwd/tmp） | 下载落盘失败 | 固定 `workspace/` 作为 cwd，`--output-dir` 限定其内 |
| 容器重建丢授权 | 需重新授权 | 凭据落 docker 卷；`credentials.enc` + `.encryption_key` 一并持久化（两者缺一不可） |
| Bot 视角 ≠ 企业全量视角 | 期望偏差（以为能查全公司） | 工具描述与 UI 文案写明「机器人视角」；全量组织数据仍走应用通道通讯录同步 |
| 与应用通道凭证混用 | 配置错误、排查困难 | 字段分列（`encrypted_token`=应用 Secret，`encrypted_bot_secret`=Bot Secret），UI 分区展示 |

## 六、待决策

1. **Bot 选择**：复用现有 Bot（`aibCQMhZJcCwvUP-ao7CJK-XfXFApdiEv9J`，需创建者续期品类授权）还是新建 superWork 专用 Bot（授权范围更干净）？
2. **品类授权维护人**：谁是企业微信侧机器人创建者？P1 的续期入口指向企微链接，需要有人实际点。
3. **P3 会议联动的取数方式**：优先用企微智能纪要（省 ASR）还是继续用录制文件走自建 ASR（可控性更高）？

## 附录 A：实测记录（2026-09-17，服务器 241）

| 步骤 | 命令/操作 | 结果 |
| --- | --- | --- |
| 本机安装 | `npm install @wecom/cli`（本地 prefix） | `wecom-cli 1.3.0 (wecom 2026-09-16T12:13:59Z dcf6929)` |
| 本机授权状态 | `wecom-cli auth show` | `Status: authorized` / `Bot ID: aibCQMhZJcCwvUP-ao7CJK-XfXFApdiEv9J` |
| 二进制形态 | `npm pack @wecom/cli-linux-x64` + `file` | `ELF 64-bit LSB pie executable, x86-64, static-pie linked, stripped`，11,417,576 字节 |
| 凭据移植 | `scp ~/.config/wecom/* 241:/tmp/wc/`（含 `.encryption_key`） | 服务器 `auth show` → `authorized`（同一 Bot） |
| 服务器执行 | `WECOM_CLI_CONFIG_DIR=/tmp/wc /tmp/wecom-cli-bin --version` | `wecom-cli 1.3.0 (wecom 2026-09-16T12:06:56Z dcf6929)` |
| 真实调用① 待办 | `todo list --limit 5` | `errcode 850003 authorization expired`（from ip: 59.46.222.98） |
| 真实调用② 会议 | `meeting list --begin-time ... --end-time ...` | `errcode 850002 no authorization` |
| 真实调用③ 通讯录 | `contact users search --keywords 张` | `errcode 850002 no authorization` |
| 真实调用④ 文档 | `doc search --keywords 周报` | `errcode 851008 partial no authorization` |
| 真实调用⑤ 消息 | `message aibot sessions list` | `errcode 850003 authorization expired` |

> 说明：以上均为只读调用；错误体一致返回 `help_message`（含企微侧续期链接），官方要求逐字展示。
> 验证用临时目录 `241:/tmp/wc`（含凭据副本，已限制权限）与 `/tmp/wecom-cli-bin`，P0 落地时移入 docker 卷；若暂不落地应清理。

## 附录 B：与现有方案的关系

- 现有《企业微信接入方案 V1》（`docs/wecom-integration-design.md`）规划的四项（token 缓存、通讯录同步、OAuth 免登、审批只读）**全部属应用通道**，与本文的机器人通道**并行不冲突**；其中「AI 助手成为日常工作入口」的愿景，机器人通道提供了比审批只读更宽的用户侧能力（待办/会议/文档），可作为 V1 之后的 V2 主线。
- 连接器收口原则不变：**企微相关的一切配置仍在「企业微信」连接器卡片内**（应用通道 + 机器人通道两个分区），不新增配置入口。
