-- ====================================
-- AI 平台地基层：统一任务队列 / Prompt 版本库 / 用量明细扩展 / 能力网关调用日志
-- 只建新表 + 给 ai_agent_usage 加列，不改存量行（存量行新列留 NULL，读侧自行兜底）
-- ====================================

-- 统一 AI 任务队列：所有「跑得慢、要重试」的 AI 动作都先落一行，由调度器抢夺式执行。
-- 抢夺式 = update ... where id = ? and status = 'PENDING'，影响行数为 1 才算抢到，
-- 因此多实例部署时同一行不会被两个实例同时执行。
CREATE TABLE IF NOT EXISTS ai_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(64) NOT NULL COMMENT '任务类型，对应一个 AiTaskHandler#type()',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/CANCELLED',
    attempt INT NOT NULL DEFAULT 0 COMMENT '已执行次数（抢夺时 +1）',
    max_attempt INT NOT NULL DEFAULT 3 COMMENT '最大执行次数，用尽后置 FAILED',
    next_run_at DATETIME NULL COMMENT '最早可执行时间（失败后按指数退避后移）',
    payload TEXT NULL COMMENT '入参 JSON，交给 handler 自己解析',
    result TEXT NULL COMMENT '成功时 handler 返回的结果 JSON',
    error VARCHAR(500) NULL COMMENT '最后一次失败原因，截 500 字',
    owner_user_id BIGINT NULL COMMENT '触发人用户 ID，配额/权限判断用',
    locked_at DATETIME NULL COMMENT '最后一次被抢走的时间，用于排查僵死行',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ai_task_due (status, next_run_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
    COMMENT = 'AI 任务队列（抢夺式执行，单行失败不影响其它行）';

-- Prompt 版本库：同一 code 多版本并存，enabled 控制线上生效版本，改 prompt 不再要发版。
CREATE TABLE IF NOT EXISTS ai_prompt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL COMMENT 'Prompt 逻辑编码，如 OPPORTUNITY_COACH',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号，从 1 递增',
    content MEDIUMTEXT NULL COMMENT 'Prompt 正文',
    model_hint VARCHAR(64) NULL COMMENT '建议使用的模型代码（空则用默认候选）',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1 = 参与线上取用，0 = 仅留档',
    created_by BIGINT NULL COMMENT '创建人用户 ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_prompt_code_version (code, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
    COMMENT = 'AI Prompt 版本库（热更新用）';

-- 用量表扩展：把「哪版 prompt / 走的是哪个任务 / 耗时多久 / 是否成功」挂到存量用量行上。
-- 纯加列，不动存量数据；历史行这些列都是 NULL，统计时按未知处理。
ALTER TABLE ai_agent_usage
    ADD COLUMN prompt_code VARCHAR(64) NULL COMMENT '实际使用的 Prompt 编码' AFTER model,
    ADD COLUMN prompt_version INT NULL COMMENT '实际使用的 Prompt 版本' AFTER prompt_code,
    ADD COLUMN latency_ms INT NULL COMMENT '整轮耗时（毫秒）' AFTER prompt_version,
    ADD COLUMN task_id BIGINT NULL COMMENT '来源 ai_task.id（非任务驱动为空）' AFTER latency_ms,
    ADD COLUMN status VARCHAR(16) NULL COMMENT 'SUCCESS/FAILED，历史行 NULL' AFTER task_id;

-- 能力网关调用日志：一次能力调用一行，用于算成功率与耗时，不存业务内容。
CREATE TABLE IF NOT EXISTS ai_capability_call (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    capability_id VARCHAR(64) NOT NULL COMMENT '能力编码，如 op.coach / skill.weekly',
    user_id BIGINT NULL COMMENT '调用人用户 ID',
    run_id VARCHAR(64) NULL COMMENT '关联的 AI 运行 ID（可选）',
    ok TINYINT NOT NULL DEFAULT 1 COMMENT '1 = 成功，0 = 失败',
    latency_ms INT NULL COMMENT '耗时（毫秒）',
    error_code VARCHAR(64) NULL COMMENT '失败错误码（成功为空）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ai_capability_call (capability_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
    COMMENT = 'AI 能力网关调用日志（只记元数据，不记业务内容）';
