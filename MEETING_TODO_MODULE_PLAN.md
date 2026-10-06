# 会议模块 + 智能总结 + 待办功能（meeting-todo-module）

## Context

在 `voice` worktree（分支 `yuxfeng275/voice`，HEAD cce7b75，仅含未跟踪的旧版 `MEETING_AUDIO_MODULE_PLAN.md`）上重新规划：会议录音上传 → 自托管 ASR 转写并匿名分人 → 人工校正 → LLM 智能总结（概览/决议/风险/待办，均带原文证据与时间戳）→ 待办草稿人工编辑 → 一键转成系统任务/事项（任务允许不带需求）。前端目标为 `frontend-pro`（Ant Design Pro/UmiJS，React 19 + antd 6 + TS strict，Biome，npm，Vitest）——不是旧 Vue `frontend/`。会议模块独立：独立 `/meetings` 菜单 + `meeting`/`meeting_speaker`/`meeting_todo` 三张表。

对旧版 `MEETING_AUDIO_MODULE_PLAN.md` 的三处根本修正（均已在本 worktree 树上核实）：
1. **迁移号 V79 已被占用**：master 上最新迁移是 `V79__connector_hub_unify.sql`（连接器收口），本模块用 **V81**。
2. **LLM 凭据不再走 system_config 组**：V79 已把 GLM/DeepSeek 连接参数收进 `ai_connector` 注册表（`ConnectorLegacyConfigMigrator` 会隐藏旧配置项），会议总结凭据复用 `AiAgentModelConfigService.resolveModelConfig("zhipu")`（GLM 优先、DeepSeek 兜底，管理端在「连接器管理」配置）。
3. **无需求任务**：用户已决策允许会议待办转任务不带需求。`task.requirement_id` 目前 NOT NULL + `TaskService.createTask` 硬校验"需求不存在"，需 V81 ALTER + 条件跳过校验。已逐一核查全部消费方（见步骤 1）：所有查询/聚合路径 null 安全或有意排除。

事实依据基于 `~/orca/workspaces/superWork-claude-sp/normal-modify`（分支 `yuxfeng275/normal-modify` = master 850e930）。下文路径均相对仓库根。

## Approach

步骤按依赖排序：0 → 1 → 2/3 可穿插，4（worker）与后端仅靠 HTTP 契约耦合可并行；7 依赖 2–5；8 最后。每步完成后可编译。

### 0. 分支对齐

在 `voice` worktree 执行 `git merge master`（当前 HEAD cce7b75 落后 master 850e930，仅 README.md 可能冲突，取 master 版）。旧 `MEETING_AUDIO_MODULE_PLAN.md` 保持未跟踪，不删除（用户可能仍需查阅对比）。

### 1. V81 迁移：`backend/src/main/resources/db/migration/V81__add_meeting_module.sql`

建三张表（utf8mb4，风格照抄 V58/V59 的 CREATE TABLE + 幂等种子写法）：

```sql
CREATE TABLE meeting (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL COMMENT '上传人（v1 可见范围=上传人）',
  title VARCHAR(200) NOT NULL,
  meeting_date DATE NOT NULL,
  project_id BIGINT NULL,
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

同一迁移追加：

1. **无需求任务 ALTER**（用户决策：允许无需求任务；`EmailConvertResult` 前端契约里 requirementId 本就可选，唯一阻碍是此列）：
   ```sql
   ALTER TABLE task MODIFY COLUMN requirement_id BIGINT NULL
     COMMENT '关联需求（NULL=独立任务，如会议待办转化）';
   ```
   保留 FK（nullable FK 对非空行约束不变）。`db/schema.sql` 是从未再更新过的初始导入参考文件（最后提交 22a9e25，Flyway 只加载 `db/migration`），不修改。
2. **配置组**（system_config_item，照抄 V47 的 INSERT 列表格式）：组 `meeting`（会议管理），仅放**会议专属开关**（LLM 凭据走连接器，不在此组）：
   - `meeting.enabled`(BOOLEAN,false)、`meeting.audio.max-size-mb`(NUMBER,100)、`meeting.asr.base-url`(URL,`http://localhost:8790`)、`meeting.asr.token`(PASSWORD,NULL)、`meeting.asr.timeout-seconds`(NUMBER,3600)、`meeting.asr.hotwords`(STRING,NULL)。
3. **菜单/权限**（照抄 V59 的幂等写法）：sys_menu 增加顶级 `/meetings`（name `会议管理`，icon `Timer`——该字符串已在 frontend-pro `menuIconByServerName` 白名单内，parent_id 0，sort_order 取 `/sec-work` 组之后）；sys_permission 增加 `meeting:view`（menu 型）与 `meeting:manage`（button 型），menu_id 经 path 子查询关联；sys_role_menu / sys_role_permission 用 `INSERT IGNORE` 授予与 V59 完全相同的五个角色：`DIRECTOR, DEPUTY_DIRECTOR, BUSINESS_OWNER, EFFECTIVENESS_OWNER, BU_ADMIN`。

不改 `issue` 表（`issue.requirement_id` 本就可空；`issue_no` 从不被 `IssueService.createIssue` 设置的问题与邮件转事项完全同路，沿用不动）。

### 2. 后端配置与基础设施

- `backend/src/main/resources/application.yml` 追加（照 `ai-agent.sidecar` 块的 `${VAR:default}` 风格）：
  ```yaml
  meeting:
    storage-dir: ${MEETING_STORAGE_DIR:./data/meetings}
  ```
- 新建 `config/MeetingProperties.java`：`@ConfigurationProperties(prefix = "meeting")`，字段 `storageDir`（照抄 `AiAgentProperties.java` 风格；ASR 连接参数运行时从 system_config `meeting` 组读取，见下）。
- 新建 `config/MeetingTaskConfig.java`：`@Bean("meetingTaskExecutor")` ThreadPoolTaskExecutor core=1, max=2, queue=20, prefix `meeting-task-`（照抄 `EmailTaskConfig.java`；不复用 emailTaskExecutor——转写动辄数十分钟会饿死邮件同步）。
- 新建 `service/MeetingConfigService.java`：GROUP=`"meeting"`，经 `SystemConfigService.getValue/getBoolean` 读取（该方法对 PASSWORD 型自动走 `EmailCredentialCipher` 解密——已核实 `SystemConfigService.getValue` 内部 `sensitive(item) ? cipher.decrypt(...)`），返回 record `MeetingRuntimeConfig(boolean enabled, String asrBaseUrl, String asrToken, int asrTimeoutSeconds, String asrHotwords, int audioMaxSizeMb)`。未启用/缺 key 不在此抛错，由调用方决定行为。
- 新建 `service/MeetingAudioStorage.java`（@Component，java.nio.file；仓库零文件存储代码——MinIO 依赖存在但 Java 侧无任何引用，附件服务是纯元数据）：`StoredAudio store(Long meetingId, String originalFilename, InputStream in)` → 相对路径 `{meetingId}.{ext}`、sizeBytes、sha256（DigestOutputStream 边写边算；先写 `{dir}/tmp/` 再原子 move）；`InputStream open(String relativePath)`；`void delete(String relativePath)`（best-effort，异常仅记日志）。

### 3. 实体 / Mapper / 服务

实体（照 `Task.java`/`EmailAction.java` 风格）：`entity/Meeting.java`、`entity/MeetingSpeaker.java`、`entity/MeetingTodo.java`；mapper 三个接口 extends BaseMapper。

- `service/MeetingService.java`：
  - `requireOwned(Long userId, Long id)` → 无权/不存在抛 `ResourceNotFoundException`（`AiAgentSessionService.requireOwned` 同款，映射 404）。
  - `updateTranscript(userId, id, List<SegmentInput>)`：写 `corrected_transcript_json`；为出现的每个新 label upsert `meeting_speaker`（display_name 默认 null）。原始 `transcript_json` 永不修改。
  - `updateSpeakers(userId, id, List<SpeakerInput>)`：更新 display_name / mapped_user_id。
  - `updateTodo(userId, id, todoId, MeetingTodoUpdateInput)`：仅 DRAFT 可改（title/description/dueText/dueDate）或 `{dismiss:true}` → DISMISSED；非 DRAFT 抛 `RuntimeException("该待办已处理")`（400）。
  - `convertTodo(userId, id, todoId, ConvertMeetingTodoInput{actionType, requirementId, assigneeId, severity, taskType})`：
    - status != DRAFT → `RuntimeException("该待办已处理")`（幂等闸门，防重复转化）。
    - TASK → `taskService.createTask(CreateTaskDTO, actorId)`。**requirementId 可空**（步骤 1 已 ALTER + TaskService 条件校验）；description 拼证据，格式克隆 `EmailActionService.sourceDescription()`：`来自会议「title」（#id）\n时间：mm:ss–mm:ss\n说话人：{display_name|label}\n\n---- 原文摘录 ----\n{source_excerpt}`（cap 1500）。assignee 缺省回填当前 userId（邮件模块同款）。
    - ISSUE → `issueService.createIssue(CreateIssueDTO, actorId)`（severity 默认 中；relatedType/relatedId 不设置，与邮件转事项一致）。
    - 回写 status=CREATED、action_type、target_id、target_title。actor 一律当前登录 userId，绝不取 body（仓库既有约定）。
  - `confirm(userId, id)`：仅 DRAFT → CONFIRMED，记 confirmed_by/at。
  - `delete(userId, id)`：删行（FK 级联 speakers/todos）+ best-effort 删音频文件。
- `service/MeetingPipelineService.java`：
  - `startProcessing(Long meetingId)`：注入 `@Resource(name="meetingTaskExecutor")`，提交 `process`。
  - `process(meetingId)`（吞掉一切异常转 FAILED）：UPLOADED→TRANSCRIBING → `MeetingTranscriptionClient.transcribe(...)` → 写 `transcript_json` + 为每个 label 建 meeting_speaker + duration_seconds → SUMMARIZING → `summarize` → DRAFT。任一步异常 → FAILED + `generation_error`（sanitize：strip、≤500 字符，同 `ConnectorRegistryService.recordTestResult` 的 cap 语义），错误不外抛。
  - `summarize(meetingId)`（DRAFT/CONFIRMED 可重跑 → SUMMARIZING）：取转写（corrected 优先，否则原始）→ 组 prompt → `MeetingSummaryClient.chatJson` → 校验并落 summary_json + meeting_todo。**重跑只替换 DRAFT 草稿：DELETE 该 meeting 的 DRAFT 行后插入新行；CREATED/DISMISSED 保留**。校验失败（缺 summary、segmentRef 越界、todo 空 title）→ `IllegalStateException` → FAILED。新增强化字段 keywords/sections/speakerPoints 宽松校验：缺失→空数组、不 FAILED；keywords 去空白去重、取前 10 个、每个 ≤20 字；sections 截前 20 个，title/content 非空字符串否则丢弃该项，startMs/endMs 为非负整数否则置 null，segmentRefs 过滤到合法 seq（全无效也保留章节项）；speakerPoints 丢弃 speaker 不在本次转写说话人集合中的项，每项 points 截前 5 条、每条 ≤100 字，segmentRefs 同样过滤。核心字段（summary/decisions/risks/todos）维持原严格规则。
  - `reprocess(meetingId)`：仅 FAILED → TRANSCRIBING 重新入队。
- 转写存储 JSON 契约（transcript_json / corrected_transcript_json 共用）：
  `{"segments":[{"seq":1,"startMs":0,"endMs":8320,"speaker":"SPEAKER_00","text":"…","edited":false}]}`
- 证据摘录一律服务端从存储的 segment.text 渲染（cap 1000），不信任模型返回的原文；dueDate 仅当落在 [meeting_date-1年, meeting_date+2年] 内才采纳，否则置 null、保留 dueText 原话。摘要 JSON 契约（summary_json，钉钉闪记式图文并茂版）：`{"summary":"…","keywords":["CDP","MA"],"sections":[{"title":"开场与目标对齐","content":"…","startMs":0,"endMs":832000,"segmentRefs":[1,2,3]}],"speakerPoints":[{"speaker":"SPEAKER_00","points":["…","…"],"segmentRefs":[1,5]}],"decisions":[…不变…],"risks":[…不变…],"todos":[…不变…]}`。发言时长统计不入 summary_json：前端详情页从转写 segments 实时聚合（说话人 × Σ(endMs-startMs)），校正稿更新后图表自动跟随。

### 4. ASR 集成（worker + 客户端）

HTTP 契约（与具体引擎解耦，后续可换引擎/云 API 而不动后端）：

- 请求：`POST {meeting.asr.base-url}/v1/transcriptions`，multipart 字段 `audio`（文件）、`hotword`（可选热词串）；header `X-Meeting-Token`（仅当 `meeting.asr.token` 非空时校验）。
- 响应 200：`{"durationMs":3725000,"model":"SenseVoiceSmall","segments":[{"index":1,"startMs":0,"endMs":8320,"speaker":"SPEAKER_00","text":"…"}]}`；错误 4xx/5xx：`{"error":{"code":"…","message":"…"}}`。
- speaker 只输出录音内匿名标签（SPEAKER_00 起，按首次出现顺序编号），契约层面禁止身份推断。
- 新建 `integration/MeetingTranscriptionClient.java`：`TranscriptionResult transcribe(Path audioFile, String fileName, String hotwords)`——java.net.http HttpClient + multipart body（连接/超时/异常包装克隆 `DeepSeekDigestClient` 风格），解析上述 JSON；超时用 `meeting.asr.timeout-seconds`（默认 3600s）。未配置 ASR（asrBaseUrl 空）→ `IllegalStateException("会议转写未配置")` → 任务 FAILED，错误信息可见。
- 新建顶层目录 `asr-worker/`（与 `ai-sidecar/` 平级，同构旁路服务）：
  - `app.py`：FastAPI + uvicorn，`GET /healthz` → `{"ok":true}`；`POST /v1/transcriptions`（python-multipart），可选 token 常量时间比较，同步处理（客户端侧控制超时）。
  - `transcribe.py`：ffmpeg 预转 16k 单声道 wav；FunASR `AutoModel` 组合 `iic/SenseVoiceSmall` + `iic/speech_fsmn_vad_zh-cn-16k-common-pytorch` + `iic/speech_campplus_sv_zh-cn_16k-common` + 标点模型（`unverified — confirm first`：若 SenseVoice 组合拿不到句级时间戳，回退用 VAD 段边界作为 startMs/endMs）；说话人聚类标签映射为 SPEAKER_NN；热词经 `hotword` 参数传入（引擎不支持时忽略，非致命）。模型下载到 `MODEL_DIR` 环境变量目录。
  - `requirements.txt`（固定版本）、`Dockerfile`（`python:3.11-slim` + apt ffmpeg，EXPOSE 8790）、`tests/test_contract.py`（stub 管线断言响应 schema；真实模型冒烟放部署验证）。

### 5. 总结生成（纯 Java，不走 sidecar agent）

- 新建 `integration/MeetingSummaryClient.java`：`JsonNode chatJson(String systemPrompt, String userInput)`——克隆 `DeepSeekDigestClient.callJson`（`response_format:{"type":"json_object"}`、temperature 0.1、`{baseUrl}/chat/completions`、Bearer key），超时 120s。**凭据与地址取 `AiAgentModelConfigService.resolveModelConfig("zhipu")`**（GLM 优先、DeepSeek 兜底；返回 `ModelConfig(baseUrl, model, apiKey)`；未就绪抛 `IllegalStateException("AI 模型未配置或未启用…")`，信息对管理员可读）。生成后记 `generation_model = baseUrl + "/" + model`。
- system prompt 要点（常量写在 MeetingPipelineService 或独立常量类）：输入为带 [seq][mm:ss][说话人] 的转写全文；输出上述摘要 JSON；待办规则——title 必须是可交付动作而非"持续跟进"；assigneeHint 只取语句中指名的人（≠发言人），没有则留空；dueText 保留原话、dueDate 仅在能由会议日期推算时输出；拿不准的承诺不生成待办；章节速览 sections：3–8 个、按时间顺序，title ≤20 字、content 1–3 句，startMs/endMs 取该章首末 segment 的 startMs/endMs，segmentRefs 列出该章包含的全部 seq；keywords：3–8 个本次会议涉及的产品/项目/业务术语（如 CDP、MA、BI），≤4 字优先；speakerPoints：每个实际发言的 speaker 输出 1–3 条要点，每条 ≤50 字并附 segmentRefs——拿不准的不要编造。
- 输入超长：转写超过 120k 字符直接 FAILED，错误提示"录音过长，请分段上传"（不做分块合并）。

### 6. REST API：`controller/MeetingController.java`（`@RequestMapping("/api/meetings")`）

类级 `@RequirePermission({"meeting:view"})`；写操作方法级 `@RequirePermission({"meeting:manage"})`（照 `EmailActionController` 写法）；actor 一律 `@RequestAttribute("userId")`；返回 `Result<T>`。`ControllerSecurityContractTest.PROTECTED_CONTROLLERS`（`backend/src/test/java/com/bu/management/controller/ControllerSecurityContractTest.java:43` 的 List.of）必须加入 `MeetingController.class`，否则契约测试失败。

| 端点 | 说明 |
|---|---|
| `POST /api/meetings` | multipart `file` + `title` + `meetingDate` + `projectId?`。校验：扩展名 ∈ mp3/wav/m4a/aac/ogg/flac/mp4/webm、大小 ≤ min(全局100MB, meeting.audio.max-size-mb)（全局 multipart cap 已是 100MB，见 application.yml）；落盘、算 sha256、建行 UPLOADED、异步入队。 |
| `GET /api/meetings?page&size&status` | 上传人维度分页列表（MyBatis-Plus Page）。 |
| `GET /api/meetings/{id}` | 详情：转写（corrected 优先）+ speakers + summary + todos。 |
| `GET /api/meetings/{id}/status` | 轻量轮询 `{status, generationError}`。 |
| `PUT /api/meetings/{id}/transcript` | 人工校正稿。 |
| `PUT /api/meetings/{id}/speakers` | 说话人命名。 |
| `POST /api/meetings/{id}/summarize` | 重跑总结（DRAFT/CONFIRMED）。 |
| `POST /api/meetings/{id}/reprocess` | FAILED 重新转写。 |
| `PUT /api/meetings/{id}/todos/{todoId}` | 编辑/忽略草稿。 |
| `POST /api/meetings/{id}/todos/{todoId}/convert` | 确认转化（body：`{actionType, requirementId?, assigneeId?, severity?, taskType?}`，requirementId 可空）。 |
| `POST /api/meetings/{id}/confirm` | DRAFT → CONFIRMED。 |
| `GET /api/meetings/{id}/audio` | owner 校验后回音频字节（Content-Type 按扩展名；前端 fetch→blob 播放，不做 Range）。 |
| `DELETE /api/meetings/{id}` | 删除（含音频文件 best-effort）。 |

**无需求任务的行为外溢（有意为之，需在提交信息注明）**：`ALTER` 后邮件转任务若 requirementId 为空也将成功建独立任务（此前在 INSERT 处失败）——`EmailActionService.convertToTask`/`toDto` 无需改码。

### 7. 前端（`frontend-pro/`，Ant Design Pro/UmiJS）

- `frontend-pro/config/routes.ts` 白名单追加两条（照 `/requirements` + `/requirements/:id` 同组件双路由模式）：
  `{ path: '/meetings', name: '会议管理', icon: 'timer', component: './meetings' }` 与 `{ path: '/meetings/:id', name: '会议详情', hideInMenu: true, component: './meetings' }`。
  侧边栏入口由 V81 sys_menu 种子经 `/api/auth/my-menu-tree` 下发（服务端驱动菜单），routes.ts 只保证可达性——**两者缺一不可**。
- `frontend-pro/src/services/superwork/api.ts`（唯一业务 API 模块）：新增 Meeting 相关 interface（照 `WeeklyReportVO` 区块风格）+ 方法挂在 `export const superworkApi`（约 1080 行处）：
  `getMeetings(params)`、`getMeeting(id)`、`getMeetingStatus(id)`、`uploadMeeting({file,title,meetingDate,projectId?})`（FormData，照 `importRevenueWorklog` 2052 行——requestJson 对 FormData 自动跳过 Content-Type）、`updateMeetingTranscript`、`updateMeetingSpeakers`、`summarizeMeeting`、`reprocessMeeting`、`updateMeetingTodo`、`convertMeetingTodo(id, todoId, payload)`（payload 类型照 `convertEmailItem` 1678 行，`requirementId?: number` 可选）、`confirmMeeting`、`deleteMeeting`、`getMeetingAudioBlob(id)`（**必须**照 `exportQuotation` 2466 行手写 Bearer fetch → `response.blob()`，requestJson 不能用于二进制）。
- Meeting interface 增补：`MeetingSummarySection { title: string; content: string; startMs: number | null; endMs: number | null; segmentRefs: number[] }`、`MeetingSpeakerPoint { speaker: string; points: string[]; segmentRefs: number[] }`；`MeetingSummary` 增加可选字段 `keywords?: string[]; sections?: MeetingSummarySection[]; speakerPoints?: MeetingSpeakerPoint[]`。
- 新建 `frontend-pro/src/pages/meetings/index.tsx` + `style.less`（页面共置约定，样式 import `'../workbench/style.less'`）：
  - 列表：状态 Statistic 卡 + antd Table（标题/日期/时长/状态徽标/操作）+ 上传 Modal（antd Upload `beforeUpload={()=>false}` 手动 FormData、标题、日期、项目可选）。
  - 轮询：照 `weekly-report/index.tsx` 的 `pollRef = useRef` 模式——存在 TRANSCRIBING/SUMMARIZING 行时 setInterval 轮询 `getMeetingStatus`，终态停止。
  - 详情（同组件内 `deepLinkId = location.pathname.match(/^\/meetings\/(\d+)$/)`，照 requirements/index.tsx 473-478 行模式）：左侧转写编辑区（每段：可点击 mm:ss 跳转音频、说话人 chip、行内改文字→保存校正稿）；右侧自上而下：`<audio controls>`（`URL.createObjectURL(blob)`）；概览卡 + 关键词 Tag 行（keywords 渲染 antd Tag，点击 seek 到首个 text 包含该词的 segment 并高亮该段 2 秒）；发言统计卡（@ant-design/plots Pie，写法照 dashboard/components/ProportionSales.tsx，数据由 computeSpeakerStats 从转写段实时聚合为 [{x: 显示名||SPEAKER_NN, y: 秒}]；图下列每说话人 chip（display_name 优先）+ speakerPoints 要点，点击要点 seek 到 segmentRefs[0]）；章节速览（antd Timeline，每项 title + content + mm:ss–mm:ss，点击 seek startMs）；决策/风险卡片（显示摘录 + 时间戳，点击 seek）、待办草稿区（可编辑 title/dueText/dueDate，按钮 转任务/转事项/忽略）、确认纪要、重新总结、失败重试。summary 缺 sections/keywords/speakerPoints（旧数据）时对应区块不渲染。
- 新建 `frontend-pro/src/pages/meetings/stats.ts`（纯函数，供组件与测试共用）：
  ```ts
  export function computeSpeakerStats(segments: { speaker: string; startMs: number; endMs: number }[]): { speaker: string; seconds: number; ratio: number }[]
  // seconds = Σ(endMs-startMs)/1000 取整；ratio = seconds/total 保留 2 位；按 seconds 降序；空输入 → []
  ```
  - 转任务对话框：字段 projectId（可选）→ requirementId（可选，项目选中后按项目过滤需求，复用 `getRequirements`/`getProjectMembers` 级联写法但**不加 required 规则**——与邮件 convert 弹窗一致而非 tasks 页创建表单）、assigneeId、severity、taskType。空 requirementId 直接提交 null。
  - 说话人命名对话框：每个 label 输入 display_name / 可选映射系统用户（`getUsers`）。
- 不需要改 `app.tsx`（icon `Timer` 已在 `menuIconByServerName` 白名单；`/meetings` 不加入 `accessForPath` 硬跳转名单，与 `/emails` 同策略——权限由后端 meeting:view/manage + sys_role_menu 控制）。
- 测试（frontend-pro 无 Playwright，测试栈为 Vitest 4 + happy-dom + @testing-library/react，共置 `*.test.tsx`）：
  1. `src/services/superwork/api.test.ts`（若无则新建）：stub 全局 fetch，断言 meeting 方法的 URL/method/payload；`uploadMeeting` 走 FormData 且不带 Content-Type；`getMeetingAudioBlob` 带 Bearer 且返回 blob。
  2. `src/pages/meetings/index.test.tsx` 增补：vi.mock superworkApi，渲染列表 + 上传 Modal 提交 FormData；vi.useFakeTimers 断言轮询在终态停止；待办 convert 在 requirementId 留空时仍以 `requirementId: undefined` 调用 `convertMeetingTodo`（本次用户决策的回归锚点）；详情 mock 返回含 keywords/sections/speakerPoints 的 summary，断言关键词 Tag 与 Timeline 章节项渲染；`vi.mock('@ant-design/plots')`，断言 Pie 收到 computeSpeakerStats 输出（说话人时长降序）。
  3. 新增 `src/pages/meetings/stats.test.ts`：多说话人秒数求和、降序排序、ratio、空数组。

### 8. 部署接线

- `docker/docker-compose.241.yml`（生产，compose 项目名 `superwork-bu`）：
  - 新增 `asr-worker` 服务（build `../asr-worker`，卷 `./asr-models:/models`，env `MODEL_DIR=/models`、`MEETING_TOKEN=${MEETING_ASR_TOKEN:-}`，healthcheck 用 python urllib——slim 镜像无 curl：`["CMD","python","-c","import urllib.request;urllib.request.urlopen('http://localhost:8790/healthz')"]`；**不发布宿主端口**，仅 bu-network 内网）。
  - backend 服务加卷 `./data/meetings:/data/meetings` 并在 environment 加 `MEETING_STORAGE_DIR: /data/meetings`（backend 当前零卷挂载，此为新增 YAML）。
  - 部署后把系统配置 `meeting.asr.base-url` 改为 `http://asr-worker:8790`（配置界面操作，非 compose）。
- `docker/docker-compose.yml`（本地 dev，**无 backend 服务**——backend 宿主机 8081 运行）：asr-worker 照 ai-sidecar 块加入并**发布 `8790:8790`**（宿主 backend 无法解析 compose DNS，base-url 保持 `http://localhost:8790`）；本地 backend 用 `MEETING_STORAGE_DIR=./data/meetings`。
- `docker/docker-compose.buai.yml`：**不改**（辅助环境，jar 挂载方式，明确不动）。
- `docs/deployment.md`（现状严重过期：只写本地 bu-management 拓扑）：补 asr-worker 一节 + 241 现实拓扑小节（服务名 superwork-bu-*、端口 18080/18081/18084、前端需宿主 `npm run build` 后 `up -d --build`）。本次唯一文档改动，属部署必需。

## Critical files & anchors

- `backend/src/main/java/com/bu/management/service/EmailActionService.java` — 转化闭环与证据摘录模板：`convertToTask`/`convertToIssue`、`sourceDescription()` 描述格式、actor 取当前用户、`requireOwnedMessage` 404 写法。
- `backend/src/main/java/com/bu/management/service/AiAgentModelConfigService.java` — `resolveModelConfig("zhipu")` → `ModelConfig(baseUrl, model, apiKey)`，GLM 优先 DeepSeek 兜底；MeetingSummaryClient 凭据唯一来源。
- `backend/src/main/java/com/bu/management/service/TaskService.java` — `createTask` L65-90 硬校验"需求不存在"改为条件校验；已核实 getTasksPage/getTaskOverview/loadRequirementMap/toOverviewItem/matchesKeyword 全部 null 安全。
- `backend/src/main/resources/db/migration/V59__add_weekly_report.sql` — 表 + 配置组 + 菜单（/sec-work 模式）+ 权限 + 五角色授权的一站式幂等迁移模板。
- `frontend-pro/src/services/superwork/api.ts` — `requestJson`（FormData 自动跳 Content-Type，L860）、multipart 模板 `importRevenueWorklog`（L2052）、blob 模板 `exportQuotation`（L2466）、convert 契约 `convertEmailItem`（L1678）。
- `frontend-pro/src/pages/dashboard/components/ProportionSales.tsx` — @ant-design/plots Pie 用法模板（colorField/label/innerRadius），会议发言统计卡照抄。

## Verification

1. 单测/门禁：`cd backend && mvn test`。新增（Mockito，仿 `TaskServiceTest`/`AiAgentSessionServiceTest`）：
   - `TaskServiceTest` 增补：requirementId=null 的 createTask 成功且落库 requirement_id 为 null；requirementId=99 仍抛"需求不存在"（保留既有用例 L153-164 不动）。
   - `MeetingPipelineServiceTest`：ASR 抛错→FAILED 且错误落库；重跑总结只替换 DRAFT 草稿；segmentRef 越界→FAILED；增补用例：summary 缺 keywords/sections/speakerPoints → 落库为空数组、不 FAILED；sections 含越界 segmentRef → 该 ref 被过滤、章节保留；speakerPoints 含未知 speaker → 该项丢弃。
   - `MeetingServiceTest`：convert 捕获 CreateTaskDTO 断言 requirementId 可空/证据描述/actor；二次 convert 被拒；requireOwned 404。
   - `MeetingAudioStorageTest`（@TempDir 存/取/删 + sha256）。
   - `ControllerSecurityContractTest` 注册 MeetingController；`MigrationV81SanityTest`（仿 `MigrationV51SanityTest`，断言迁移含三表 + task ALTER + `meeting:view`）。
   - JaCoCo 既有门禁（BUNDLE LINE 0.80 / BRANCH 0.60）必须过。
2. worker 契约：`cd asr-worker && pip install -r requirements.txt && python -m pytest tests -q`（stub，无模型下载）。
3. 前端：`cd frontend-pro && npm run lint && npx antd lint ./src && npm run test`（Vitest）。
4. 本地链路冒烟（不下载模型）：起 stub worker（临时脚本返回 2 段固定 segments，验证后删除）→ 宿主 backend 设 `MEETING_STORAGE_DIR=./data/meetings` + `docker compose -f docker/docker-compose.yml up -d asr-worker` → `curl -F file=@sample.m4a -F title=冒烟 -F meetingDate=2026-09-16 -H "Authorization: Bearer <token>" http://localhost:8081/api/meetings` → 轮询 `GET /{id}/status` 到 DRAFT → `GET /{id}` 见分段、概览、关键词、章节速览、发言统计与每人要点 → convert 一条待办 **不带 requirementId** → 任务列表出现独立任务（无需求归属，详情需求列显示 `—`）且描述含"原文摘录"。
5. 真实模型与部署：`docker compose -f docker/docker-compose.241.yml up -d --build asr-worker backend frontend-pro`（frontend-pro 先宿主 `npm run build`）→ `docker compose -f docker/docker-compose.241.yml ps` 确认 asr-worker healthy → 查 `flyway_schema_history` 确认 V81 应用 → 管理端启用 meeting 组、`meeting.asr.base-url=http://asr-worker:8790`、连接器管理确认 GLM 就绪 → 上传一段双人真实录音：转写文字与时间戳合理、≥2 个匿名说话人标签、改名后摘要与待办引用新名字、待办带可回听时间戳、章节 3–8 个且时间戳落在录音时长内、发言统计图与实际发言时长一致、`http://<241>:18084/meetings` 对五个授权角色可见（旧前端 :18080 不受影响）→ 用三类录音（清晰轮流发言 / 多人抢话 / 含 CDP、MA、BI 等术语）人工评估转写与分人质量并记录结果（准确率验收属人工判断，不写自动断言）。

## Assumptions & contingencies

- **无需求任务的消费方影响（已核实并接受）**：null requirementId 任务出现在不过滤的任务列表/概览中（requirement/project 列显示空）；BuDashboardService/StatisticsService 的 `in(requirementId)` 聚合**有意排除**独立任务（方向进度统计不含散任务）；WorkLogMapper→KpiSnapshotService 劳动估算跳过 null（`Objects::nonNull` 过滤）；任务页创建表单仍保留前端 required 规则（UI 选择，与后端解耦）。
- **自托管 FunASR worker**（隐私优先、录音不出内网）；若实测 CPU 速度或质量不可接受，备选 = worker 内换 MOSS-Transcribe-Diarize（HTTP 契约不变，只改 `asr-worker/` 内部）或云 ASR（`MeetingTranscriptionClient` 加 provider 分支，base-url/token 配置项已预留）。
- **音频存本地文件系统卷**而非 MinIO（MinIO 容器虽在 241 部署但 Java 侧零代码引用）；若运维要求必须 MinIO，仅重写 `MeetingAudioStorage` 一个类。
- **总结 LLM 复用连接器凭据**（`resolveModelConfig("zhipu")`），不再建第二个 GLM 凭据存储（V79 收口方向）；若产品要求会议专用模型与助手隔离，后续在 `meeting` 配置组加 model 键覆盖 connector extra 的 model 即可，客户端结构不变。
- 会议可见范围 v1 = 上传人本人（requireOwned，对齐邮件 owner 隔离的保守默认）；团队共享/项目维度可见留待后续需求。
