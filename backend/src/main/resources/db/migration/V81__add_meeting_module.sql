-- ====================================
-- V81: 会议模块（录音上传 → ASR 转写分人 → 人工校正 → LLM 图文并茂总结 → 待办转化）
-- 1) meeting / meeting_speaker / meeting_todo 三张表
-- 2) task.requirement_id 放开为 NULL（允许无需求任务；会议待办可转为独立任务）
-- 3) meeting 配置组（模块开关/音频上限/ASR 连接参数；LLM 凭据走连接器，不在此组）
-- 4) 顶级菜单 /meetings（会议管理）+ 权限 meeting:view / meeting:manage + 五管理角色授权
-- ====================================

-- 1. 会议主表（transcript_json 不可变，人工校正另存 corrected_transcript_json）
CREATE TABLE IF NOT EXISTS meeting (
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

-- 1.1 录音内匿名说话人（worker 输出 SPEAKER_NN，人工命名/映射系统用户）
CREATE TABLE IF NOT EXISTS meeting_speaker (
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

-- 1.2 会议待办草稿（AI 生成 → 人工编辑 → 转任务/事项）
CREATE TABLE IF NOT EXISTS meeting_todo (
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

-- 2. 允许无需求任务：requirement_id 放开为 NULL（保留 requirement FK，nullable 后仅约束非空行）
ALTER TABLE task MODIFY COLUMN requirement_id BIGINT NULL
    COMMENT '关联需求（NULL=独立任务，如会议待办转化）';

-- 3. 配置组 meeting（会议管理）
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT * FROM (
    SELECT 'meeting' AS group_code, '会议管理' AS group_name, '会议录音转写与智能总结相关配置' AS group_description,
           'meeting.enabled' AS config_key, '启用会议模块' AS config_name, '是否允许上传会议录音并生成纪要' AS config_description,
           'BOOLEAN' AS value_type, 'false' AS config_value, 0 AS is_sensitive, 1 AS is_required, 10 AS sort_order, 1 AS status
    UNION ALL
    SELECT 'meeting', '会议管理', '会议录音转写与智能总结相关配置',
           'meeting.audio.max-size-mb', '录音大小上限(MB)', '单次上传录音最大体积，与全局 multipart 上限取较小值',
           'NUMBER', '100', 0, 1, 20, 1
    UNION ALL
    SELECT 'meeting', '会议管理', '会议录音转写与智能总结相关配置',
           'meeting.asr.base-url', 'ASR 服务地址', '自托管转写 worker 根地址',
           'URL', 'http://localhost:8790', 0, 1, 30, 1
    UNION ALL
    SELECT 'meeting', '会议管理', '会议录音转写与智能总结相关配置',
           'meeting.asr.token', 'ASR 访问令牌', 'worker 侧 X-Meeting-Token 校验凭据，加密保存',
           'PASSWORD', NULL, 1, 0, 40, 1
    UNION ALL
    SELECT 'meeting', '会议管理', '会议录音转写与智能总结相关配置',
           'meeting.asr.timeout-seconds', 'ASR 超时(秒)', '单次转写请求超时时间',
           'NUMBER', '3600', 0, 1, 50, 1
    UNION ALL
    SELECT 'meeting', '会议管理', '会议录音转写与智能总结相关配置',
           'meeting.asr.hotwords', 'ASR 热词', '传给转写引擎的领域热词（逗号分隔），为空不启用',
           'STRING', NULL, 0, 0, 60, 1
) seed
WHERE NOT EXISTS (
    SELECT 1 FROM system_config_item i
    WHERE i.group_code = 'meeting' AND i.config_key = seed.config_key
);

-- 4. 菜单：会议管理（顶级，紧随 /sec-work 组 sort=1 之后）
INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '会议管理', 'Timer', '/meetings', 'MeetingView', 2, 1, 1 FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/meetings');

-- 5. 权限
INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'meeting:view', '查看会议', '查看会议列表、详情、录音与纪要', 'menu', menu.id
FROM sys_menu menu WHERE menu.path = '/meetings'
  AND NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'meeting:view');

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'meeting:manage', '管理会议', '上传录音、校正转写、重跑总结、转化待办', 'button', menu.id
FROM sys_menu menu WHERE menu.path = '/meetings'
  AND NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'meeting:manage');

-- 6. 管理角色授权（菜单 + 权限），与 V59 周报同角色集合
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r
JOIN sys_menu m ON m.path = '/meetings'
WHERE r.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
JOIN sys_permission p ON p.code IN ('meeting:view', 'meeting:manage')
WHERE r.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN');
