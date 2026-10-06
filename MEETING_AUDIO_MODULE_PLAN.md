# 会议录音转写与纪要模块（meeting-audio-module）

## Context

在 superWork 电商BU管理系统（Spring Boot 3.2.4 + MyBatis-Plus + MySQL/Flyway 后端，Vue 3 + Element Plus 前端）中新增"会议录音"模块：上传录音 → 自托管 ASR 转写并匿名区分发言人 → 人工校正文字与命名发言人 → LLM 生成总结/决议/风险/待办（每条带原文证据与时间戳）→ 人工确认后将待办一键转成系统任务/事项。第一版只做录音内匿名分人 + 人工改名，不做跨会议声纹认人；所有 AI 产物都是草稿，人工确认后才落 `task`/`issue`。

实现位置：当前 `voice` worktree（分支 `yuxfeng275/voice`）。调研依据来自同仓库 `normal-modify` worktree（master 同一提交树，迁移已到 V78）。下文路径均相对仓库根。

## Approach

步骤按依赖排序：0 → 1 → 2/3 可穿插，4（worker）与后端仅靠 HTTP 契约耦合，可并行实现；6 依赖 2–5；7 依赖 6；8 最后。每步完成后项目可编译。

### 0. 分支对齐

在 `voice` worktree 执行 `git merge master`。`yuxfeng275/voice` 当前 HEAD（cce7b75）是 master（8f86df7）的祖先，应干净 fast-forward 到含 V78 迁移的最新代码。若因远端更新产生冲突，README.md 冲突取 master 版本，其余逐个解决。

### 1. V79 迁移：`backend/src/main/resources/db/migration/V79__add_meeting_module.sql`

建三张表（utf8mb4，风格照抄 V58/V59）：

```sql
CREATE TABLE meeting (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL COMMENT '上传人（v1 可见范围=上传人）',
  title VARCHAR(200) NOT NULL,
  meeting_date DATE NOT NULL,
  project_id BIGINT NULL,
  business_line_id BIGINT NULL,
  duration_seconds INT NULL,
  audio_file_path VARCHAR(512) NOT NULL COMMENT '存储相对路径',
  audio_size_bytes BIGINT NOT NULL,
  audio_sha256 CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED'
    COMMENT 'UPLOADED/TRANSCRIBING/SUMMARIZING/DRAFT/CONFIRMED/FAILED',
  transcript_json MEDIUMTEXT NULL COMMENT '原始转写（不可变）',
  corrected_transcript_json MEDIUMTEXT NULL COMMENT '人工校正稿',
  summary_json MEDIUMTEXT NULL,
  generation_model VARCHAR(100) NULL,
  generation_error VARCHAR(500) NULL,
  confirmed_by BIGINT NULL,
  confirmed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_meeting_owner (owner_user_id, updated_at),
  INDEX idx_meeting_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会议录音与纪要';

CREATE TABLE meeting_speaker (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  meeting_id BIGINT NOT NULL,
  speaker_label VARCHAR(32) NOT NULL COMMENT 'worker 输出的匿名标签 SPEAKER_00',
  display_name VARCHAR(64) NULL COMMENT '人工命名',
  mapped_user_id BIGINT NULL COMMENT '人工映射到系统用户，仅展示用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_meeting_speaker (meeting_id, speaker_label),
  CONSTRAINT fk_meeting_speaker_meeting FOREIGN KEY (meeting_id)
    REFERENCES meeting(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='录音内匿名说话人人工命名';

CREATE TABLE meeting_todo (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  meeting_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NULL,
  assignee_hint VARCHAR(64) NULL COMMENT 'AI 提示的负责人人名（≠发言人）',
  due_text VARCHAR(200) NULL COMMENT '截止时间原话，如"下周五"',
  due_date DATE NULL COMMENT '仅当能由 meeting_date 推算时填写',
  source_segment_seq INT NULL,
  source_start_ms BIGINT NULL,
  source_end_ms BIGINT NULL,
  source_excerpt VARCHAR(1000) NULL COMMENT '服务端从转写段渲染的原文摘录',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CREATED/DISMISSED',
  action_type VARCHAR(16) NULL COMMENT 'TASK/ISSUE（转化后回填）',
  target_id BIGINT NULL,
  target_title VARCHAR(200) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_meeting_todo_meeting (meeting_id, status),
  CONSTRAINT fk_meeting_todo_meeting FOREIGN KEY (meeting_id)
    REFERENCES meeting(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会议待办草稿';
```

同一迁移追加（照抄 V47 的 system_config_item INSERT 元组格式、V59 的菜单/权限段）：

- 配置组 `meeting`（会议录音）：`meeting.enabled`(BOOLEAN,false)、`meeting.asr.base-url`(URL,`http://localhost:8790`)、`meeting.asr.token`(PASSWORD,NULL)、`meeting.asr.timeout-seconds`(NUMBER,3600)、`meeting.asr.hotwords`(STRING,NULL)、`meeting.llm.enabled`(BOOLEAN,false)、`meeting.llm.base-url`(URL,`https://open.bigmodel.cn/api/paas/v4`)、`meeting.llm.model`(STRING,`glm-5.3`)、`meeting.llm.api-key`(PASSWORD,NULL)、`meeting.audio.max-size-mb`(NUMBER,100)。密钥用 PASSWORD 型，运行时经 `EmailCredentialCipher` AES-256-GCM 解密（V29/V47 既有机制）。
- 菜单/权限：sys_menu 增加 `/meetings`（component `MeetingView`，父级与 V59 的周报同挂 `/sec-work` 下）；sys_permission 增加 `meeting:view`（菜单型）、`meeting:manage`（按钮型）；sys_role_menu / sys_role_permission 授予与 V59 周报完全相同的角色集合。用 V59 的幂等写法（WHERE NOT EXISTS / ON DUPLICATE KEY）。

不改 `task`/`issue`/`attachment` 表。

### 2. 后端配置与基础设施

- `backend/src/main/resources/application.yml` 追加：
  ```yaml
  meeting:
    storage-dir: ${MEETING_STORAGE_DIR:./data/meetings}
  ```
- 新建 `config/MeetingProperties.java`：`@ConfigurationProperties(prefix = "meeting")`，字段 `storageDir`（照抄 `AiAgentProperties.java` 风格）。
- 新建 `config/MeetingTaskConfig.java`：`@Bean("meetingTaskExecutor")` ThreadPoolTaskExecutor core=1, max=2, queue=20, prefix `meeting-task-`（照抄 `EmailTaskConfig.java`；不要复用 emailTaskExecutor——转写动辄数十分钟会饿死邮件同步）。
- 新建 `service/MeetingConfigService.java`：GROUP=`"meeting"`，`record MeetingRuntimeConfig(boolean enabled, boolean asrEnabled, String asrBaseUrl, String asrToken, int asrTimeoutSeconds, String asrHotwords, boolean llmEnabled, String llmBaseUrl, String llmModel, String llmApiKey, int audioMaxSizeMb)`，`getRuntimeConfig()` 从 `SystemConfigService` 读取（克隆 `AiAgentModelConfigService` 的取值方式；未启用/缺 key 不在此抛错，由调用方决定行为）。
- 新建 `service/MeetingAudioStorage.java`（@Component，java.nio.file，无对应既有实现——附件上传是纯元数据，MinIO 依赖无人使用）：
  - `StoredAudio store(Long meetingId, String originalFilename, InputStream in)` → 相对路径 `{meetingId}.{ext}`、sizeBytes、sha256（DigestOutputStream 边写边算）；先写 `{dir}/tmp/` 再原子 move。
  - `InputStream open(String relativePath)`、`void delete(String relativePath)`（best-effort，异常仅记日志）。

### 3. 实体 / Mapper / 服务

实体（照 `Task.java`/`EmailAction.java` 风格）：`entity/Meeting.java`、`entity/MeetingSpeaker.java`、`entity/MeetingTodo.java`；mapper 三个接口 extends BaseMapper。

- `service/MeetingService.java`：
  - `requireOwned(Long userId, Long id)` → 无权/不存在抛 `ResourceNotFoundException`（`AiAgentSessionService` 同款）。
  - `updateTranscript(userId, id, List<SegmentInput>)`：写 `corrected_transcript_json`；为出现的每个新 label upsert `meeting_speaker`（display_name 默认 null）。原始 `transcript_json` 永不修改。
  - `updateSpeakers(userId, id, List<SpeakerInput>)`：更新 display_name / mapped_user_id。
  - `updateTodo(userId, id, todoId, MeetingTodoUpdateInput)`：仅 DRAFT 可改（title/description/dueText/dueDate）或 `{dismiss:true}` → DISMISSED。
  - `convertTodo(userId, id, todoId, ConvertMeetingTodoInput{actionType, requirementId, assigneeId, severity, taskType})`：
    - status != DRAFT → `RuntimeException("该待办已处理")`（400，防重复转化，幂等闸门）。
    - TASK → `taskService.createTask(CreateTaskDTO, actorId)`。`requirementId` 必填（`TaskService.createTask` 会校验"需求不存在"，硬约束）；description 拼证据，格式克隆 `EmailActionService.sourceDescription()`：`来自会议「title」（#id）\n时间：mm:ss–mm:ss\n说话人：{display_name|label}\n\n---- 原文摘录 ----\n{source_excerpt}`。
    - ISSUE → `issueService.createIssue(CreateIssueDTO, actorId)`（severity 默认 中）。
    - 回写 status=CREATED、action_type、target_id、target_title。注意 `createTask`/`createIssue` 的 actor 一律传当前登录 userId，绝不取 body（仓库既有约定）。
  - `confirm(userId, id)`：仅 DRAFT → CONFIRMED，记 confirmed_by/at。
  - `delete(userId, id)`：删行（FK 级联 speakers/todos）+ best-effort 删音频文件。
- `service/MeetingPipelineService.java`：
  - `startProcessing(Long meetingId)`：注入 `@Resource(name="meetingTaskExecutor")`，提交 `process`。
  - `process(meetingId)`（吞掉一切异常转 FAILED）：UPLOADED→TRANSCRIBING → `MeetingTranscriptionClient.transcribe(...)` → 写 `transcript_json` + 为每个 label 建 meeting_speaker + duration_seconds → SUMMARIZING → `summarize` 内部逻辑 → DRAFT。任一步异常 → FAILED + `generation_error`（sanitize：strip、≤500 字符，克隆 `EmailInterpretationService.sanitize` 语义），错误不外抛。
  - `summarize(meetingId)`（DRAFT/CONFIRMED 可重跑 → SUMMARIZING）：取转写（corrected 优先，否则原始）→ 组 prompt → `MeetingSummaryClient.chatJson` → 校验并落 summary_json + meeting_todo。**重跑只替换 DRAFT 草稿：DELETE 该 meeting 的 DRAFT 行后插入新行；CREATED/DISMISSED 保留**。校验失败（缺 summary、segmentRef 越界、todo 空 title）→ `IllegalStateException` → FAILED。
  - `reprocess(meetingId)`：仅 FAILED → TRANSCRIBING 重新入队。
- 转写存储 JSON 契约（transcript_json / corrected_transcript_json 共用）：
  `{"segments":[{"seq":1,"startMs":0,"endMs":8320,"speaker":"SPEAKER_00","text":"…","edited":false}]}`
- 摘要 JSON 契约（summary_json）：
  `{"summary":"…","decisions":[{"content":"…","segmentRefs":[3,4]}],"risks":[{"content":"…","severity":"高|中|低","segmentRefs":[7]}],"todos":[{"title":"…","description":"…","assigneeHint":"小李","dueText":"下周五","dueDate":"2026-09-25","segmentRef":12}]}`
  证据摘录一律服务端从存储的 segment.text 渲染（cap 1000），不信任模型返回的原文；dueDate 仅当落在 [meeting_date-1年, meeting_date+2年] 内才采纳，否则置 null、保留 dueText 原话（不擅自换算）。

### 4. ASR 集成（worker + 客户端）

契约（与具体引擎解耦，后续可换 MOSS-Transcribe-Diarize 或云 API 而不动后端）：

- 请求：`POST {meeting.asr.base-url}/v1/transcriptions`，multipart 字段 `audio`（文件）、`hotword`（可选，热词串）；header `X-Meeting-Token`（仅当 `meeting.asr.token` 非空时校验）。
- 响应 200：`{"durationMs":3725000,"model":"SenseVoiceSmall","segments":[{"index":1,"startMs":0,"endMs":8320,"speaker":"SPEAKER_00","text":"…"}]}`；错误 4xx/5xx：`{"error":{"code":"…","message":"…"}}`。
- speaker 只输出录音内匿名标签（SPEAKER_00 起，按首次出现顺序编号），契约层面禁止身份推断。
- Java 侧新建 `integration/MeetingTranscriptionClient.java`：`TranscriptionResult transcribe(Path audioFile, String fileName, String hotwords)`，HttpClient + multipart body（`DeepSeekDigestClient` 的连接/超时/异常包装风格），读 `choices` 不适用——直接解析上述 JSON；超时用 `meeting.asr.timeout-seconds`（默认 3600s，1–2 小时 CPU 转写留足余量）。未启用 ASR（asrEnabled=false）→ `IllegalStateException("会议转写未配置")` → 任务 FAILED，错误信息可见。
- 新建顶层目录 `asr-worker/`（与 `ai-sidecar/` 平级，同构的旁路服务）：
  - `app.py`：FastAPI + uvicorn，`GET /healthz` → `{"ok":true}`；`POST /v1/transcriptions`（python-multipart），可选 token 校验（常量时间比较），同步处理（客户端侧控制超时）。
  - `transcribe.py`：ffmpeg 预转 16k 单声道 wav；FunASR `AutoModel` 组合 `iic/SenseVoiceSmall` + `iic/speech_fsmn_vad_zh-cn-16k-common-pytorch` + `iic/speech_campplus_sv_zh-cn_16k-common` + 标点模型（FunASR 官方 README 推荐的"转写+VAD+CAM++ 分人"组合；`unverified — confirm first`：若 SenseVoice 组合拿不到句级时间戳，回退用 VAD 段边界作为 startMs/endMs）；说话人聚类标签映射为 SPEAKER_NN；热词经 `hotword` 参数传入（引擎不支持时忽略，非致命）。模型下载到 `MODEL_DIR` 环境变量目录（挂载卷，容器启动时首次下载）。
  - `requirements.txt`（固定版本）、`Dockerfile`（`python:3.11-slim` + apt ffmpeg，EXPOSE 8790）、`tests/test_contract.py`（stub 管线，断言响应 schema；真实模型冒烟放在部署验证）。

### 5. 总结生成（纯 Java，不走 sidecar agent）

- 新建 `integration/MeetingSummaryClient.java`：`JsonNode chatJson(String systemPrompt, String userInput)` —— 克隆 `DeepSeekDigestClient.callJson`（`response_format:{"type":"json_object"}`、temperature 0.1、`{llmBaseUrl}/chat/completions`、Bearer key），超时 120s。配置取 `MeetingConfigService` 的 llm.*（独立于 AI 助手的 `ai-agent` 组，避免助手停用拖垮会议模块）；未启用 → `IllegalStateException("会议纪要模型未配置")`。
- system prompt 要点（写死在 MeetingPipelineService 或独立常量类）：输入为带 [seq][mm:ss][说话人] 的转写全文；输出上述摘要 JSON；待办规则——title 必须是可交付动作而非"持续跟进"；assigneeHint 只取语句中指名的人（≠发言人），没有则留空；dueText 保留原话、dueDate 仅在能由会议日期推算时输出；拿不准的承诺不生成待办。
- 输入超长（>约 100k 字符）时按 60s 窗口分块合并？——不做。第一版限制：转写超过 120k 字符直接 FAILED，错误提示"录音过长，请分段上传"。避免分块合并的复杂度。

### 6. REST API：`controller/MeetingController.java`（`@RequestMapping("/api/meetings")`）

类级 `@RequirePermission({"meeting:view"})`；写操作方法级 `@RequirePermission({"meeting:manage"})`（convert 端点的注解照抄 `EmailActionController` convert 的写法）；actor 一律 `@RequestAttribute("userId")`；返回 `Result<T>` 统一包裹。`ControllerSecurityContractTest.PROTECTED_CONTROLLERS` 必须加入 MeetingController，否则契约测试失败。

| 端点 | 说明 |
|---|---|
| `POST /api/meetings` | multipart `file` + `title` + `meetingDate` + `projectId?` + `businessLineId?`。校验：扩展名 ∈ mp3/wav/m4a/aac/ogg/flac/mp4/webm、大小 ≤ min(全局100MB, meeting.audio.max-size-mb)；落盘、算 sha256、建行 UPLOADED、异步入队。 |
| `GET /api/meetings?page&size&status` | 上传人维度分页列表（MyBatis-Plus Page，仓库既有分页返回格式）。 |
| `GET /api/meetings/{id}` | 详情：转写（corrected 优先）+ speakers + summary + todos。 |
| `GET /api/meetings/{id}/status` | 轻量轮询 `{status, generationError}`。 |
| `PUT /api/meetings/{id}/transcript` | 人工校正稿。 |
| `PUT /api/meetings/{id}/speakers` | 说话人命名。 |
| `POST /api/meetings/{id}/summarize` | 重跑总结（DRAFT/CONFIRMED）。 |
| `POST /api/meetings/{id}/reprocess` | FAILED 重新转写。 |
| `PUT /api/meetings/{id}/todos/{todoId}` | 编辑/忽略草稿。 |
| `POST /api/meetings/{id}/todos/{todoId}/convert` | 确认转化（body：actionType/requirementId/assigneeId/severity/taskType）。 |
| `POST /api/meetings/{id}/confirm` | DRAFT → CONFIRMED。 |
| `GET /api/meetings/{id}/audio` | owner 校验后回音频字节（Content-Type 按扩展名；前端 fetch→blob 播放，不做 Range）。 |
| `DELETE /api/meetings/{id}` | 删除（含音频文件 best-effort）。 |

### 7. 前端（`frontend/`，Vue 3 + Element Plus）

- `frontend/src/types/meeting.ts`：TranscriptSegment、MeetingSpeaker、MeetingSummary、MeetingTodo、MeetingStatus 联合类型、MeetingDetail（仿 `types/ai-agent.ts`）。
- `frontend/src/utils/api.ts` 追加方法：uploadMeeting（FormData，仿既有 multipart 上传写法）、getMeetings、getMeetingDetail、getMeetingStatus、updateMeetingTranscript、updateMeetingSpeakers、summarizeMeeting、reprocessMeeting、updateMeetingTodo、convertMeetingTodo、confirmMeeting、getMeetingAudioBlob（fetch→Blob）、deleteMeeting。
- `frontend/src/views/MeetingView.vue`：列表（标题/日期/时长/状态徽标/操作）+ 上传对话框（el-upload accept 音频、标题、日期、项目可选）；存在 TRANSCRIBING/SUMMARIZING 行时 setInterval 轮询（`EmailManagementView` 模式，终态停止）。
- `frontend/src/views/MeetingDetailView.vue`：左侧转写编辑区（每段：可点击 mm:ss 跳转音频、说话人 chip、行内改文字→保存校正稿）；右侧：`<audio controls>`（blob URL）、概览 + 决策/风险卡片（均显示摘录 + 时间戳，点击跳转）、待办草稿区（可编辑 title/dueText，按钮 转任务/转事项/忽略）、确认纪要、重新总结、失败重试。转任务对话框克隆 `TasksView.vue` 建任务对话框的 项目→需求 级联 + 项目成员负责人 + taskType（requirementId 必填由后端硬约束兜底）。说话人命名对话框：每个 label 输入 display_name / 可选映射系统用户。
- `frontend/src/router/index.ts`：MainLayout children 追加 `{ path: 'meetings', component: MeetingView }` 与 `{ path: 'meetings/:id', component: MeetingDetailView }`（meta.title 中文）。侧边栏入口来自 V79 种子菜单，无菜单则页面不可达（V57 动态菜单机制）。
- `frontend/tests/meetings.spec.ts`：Playwright + route.fulfill mock（仿 `tasks-create.spec.ts`）：上传对话框发 POST、轮询到 DRAFT、详情渲染分段、转任务对话框未选需求时禁止提交。

### 8. 部署接线

- `docker/docker-compose.yml` 与 `docker/docker-compose.241.yml`：
  - 新增 `asr-worker` 服务（build ./asr-worker，卷 `./asr-models:/models`，env `MODEL_DIR=/models`、`MEETING_TOKEN=${MEETING_ASR_TOKEN:-}`，healthcheck curl /healthz，端口仅内网 8790）。
  - backend 服务加卷 `./data/meetings:/data/meetings` 并设 `MEETING_STORAGE_DIR=/data/meetings`（241 卷命名/路径照该文件既有约定）。
  - 241 环境把 `meeting.asr.base-url` 系统配置项改为 `http://asr-worker:8790`（或部署后界面配置）。
- `docs/deployment.md` 补 asr-worker 一节（本次唯一文档改动，属部署必需）。

## Critical files & anchors

- `backend/src/main/java/com/bu/management/service/EmailActionService.java` — 转化闭环与证据摘录模板：`sourceDescription()` 的描述格式、actor 取当前用户、幂等闸门写法。
- `backend/src/main/java/com/bu/management/integration/DeepSeekDigestClient.java` — JSON-mode LLM 客户端模板：`callJson`、strict 校验、异常包装、sanitize。
- `backend/src/main/resources/db/migration/V59__add_weekly_report.sql` — 表 + 配置组 + 菜单/权限种子的一站式迁移模板（含角色授予集合）。
- `backend/src/main/java/com/bu/management/service/AiAgentModelConfigService.java` — system_config 组解析模板（enabled/base-url/model/api-key 取值方式）。
- `docker/docker-compose.241.yml` — 241 部署的现有服务接线（backend 卷、ai-sidecar env），asr-worker 照此风格加入。

## Verification

1. 单测/门禁：`cd backend && mvn test`。新增（Mockito，仿 `AiAgentSessionServiceTest`）：MeetingPipelineServiceTest（ASR 抛错→FAILED 且错误落库；重跑总结只替换 DRAFT 草稿；segmentRef 越界→FAILED）、MeetingServiceTest（convert 捕获 CreateTaskDTO 断言 requirementId/证据描述/actor；二次 convert 被拒；requireOwned 404）、MeetingAudioStorageTest（@TempDir 存/取/删 + sha256）；`ControllerSecurityContractTest` 注册 MeetingController；`MigrationV79SanityTest` 断言迁移含三表与 `meeting:view`（仿 `MigrationV51SanityTest`）。JaCoCo 既有 0.80/0.60 门禁必须过。
2. worker 契约：`cd asr-worker && pip install -r requirements.txt && python -m pytest tests -q`（stub，无模型下载）。
3. 本地链路冒烟（不下载模型）：起一个 stub worker（临时脚本，返回 2 段固定 segments 后删除）→ `curl -F file=@sample.m4a -F title=冒烟 -F meetingDate=2026-09-16 -H "Authorization: Bearer <token>" http://localhost:8081/api/meetings`（端口按本地配置）→ 轮询 `GET /{id}/status` 到 DRAFT → `GET /{id}` 见分段与摘要 → convert 一条待办（body 带 requirementId）→ TasksView 出现对应任务且描述含"原文摘录"。
4. 真实模型：`docker compose -f docker/docker-compose.yml up -d asr-worker`（首次下载模型到 ./asr-models）→ 系统配置启用 meeting 组并填 llm api-key → 上传一段双人真实录音：期望转写文字与时间戳合理、产生 ≥2 个匿名说话人标签、改名后摘要与待办引用新名字、待办带可回听时间戳。
5. 前端与部署：`pnpm install && pnpm -C frontend exec playwright test tests/meetings.spec.ts`；按 `docs/deployment.md` + AGENTS.md 交付偏好部署到 241（compose up -d --build asr-worker backend，前端构建发布），在部署 URL 用真实会议录音完整走一遍；用三类录音（清晰轮流发言 / 多人抢话 / 含 CDP、MA、BI 等术语）人工评估转写与分人质量并记录结果（准确率验收属人工判断，不写自动断言）。

## Assumptions & contingencies

- **自托管 FunASR worker**（隐私优先、免费，符合录音不出内网的要求）；若实测 CPU 速度或质量不可接受，备选路径 = worker 内换 MOSS-Transcribe-Diarize（HTTP 契约不变，只改 `asr-worker/` 内部）或接云 ASR（需在 `MeetingTranscriptionClient` 内加 provider 分支，配置项已预留 base-url/model）。
- **存储用本地文件系统卷**而非 MinIO（MinIO 依赖存在但仓库零代码使用，241 是否运行 MinIO 未验证）；若运维要求必须 MinIO，仅重写 `MeetingAudioStorage` 一个类，其余不动。
- **总结 LLM 走新 `meeting` 配置组**（默认指向 GLM 端点），不与 AI 助手共用 key/开关；管理员需启用并填 key，否则总结阶段 FAILED 且错误信息可读。
- `issue` 表 `issue_no` 疑似与 `IssueService.createIssue` 不一致（V1 声明 NOT NULL 但服务从不设置）——该路径与线上邮件转事项完全同路，邮件模块已在生产使用，故沿用；若部署环境真因 issue_no 报错，按邮件模块同款方式处理，不在本特性内改表。
- 会议可见范围 v1 = 上传人本人（对齐邮件 owner 隔离的保守默认）；团队共享/项目维度可见留待后续需求。
