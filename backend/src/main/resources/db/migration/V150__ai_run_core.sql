-- ====================================
-- V150: 编排内核 —— run / node / invocation 三张表
-- 设计见 docs/ai-night-cycle/03-functions.md §3.2。
-- 1) ai_run：一次流程实例。uk_run_biz 让同一业务日同项目同 SOP 只建一个 run（重复触发拿同一条）。
-- 2) ai_run_node：SOP 节点。deps 存前驱 node_key；epoch 防旧 worker 覆盖新状态。
-- 3) ai_invocation：工具调用前置记录。call_id 唯一；logical_action_key 提供跨重试幂等；
--    有 started_at 无 finished_at 的行就是「结果未知」的唯一依据。
-- 注意：这里只建表与状态字段，人工闸门表见 V151。
-- ====================================

CREATE TABLE IF NOT EXISTS ai_run (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_no           VARCHAR(32)  NOT NULL COMMENT '对外编号，如 NR-20261122-01',
    kind             VARCHAR(24)  NOT NULL COMMENT 'NIGHT_BATCH/SYNC_CHECK/M2_QUEUE/ADHOC',
    sop_code         VARCHAR(64)  NOT NULL,
    sop_version      INT          NOT NULL,
    project_id       BIGINT       NULL,
    -- 生成列：MySQL 的唯一索引对 NULL 不去重（NULL != NULL），用 0 代替「不针对具体项目」，
    -- 否则不带项目的 run 每次触发都会插入一条新的
    project_key      BIGINT       AS (IFNULL(project_id, 0)) STORED,
    owner_user_id    BIGINT       NOT NULL COMMENT '以谁的身份跑（必须是真实成员）',
    biz_date         DATE         NOT NULL,
    trigger_source   VARCHAR(24)  NOT NULL COMMENT 'AUTOLOOP/CRON/API/EVENT',
    status           VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/WAITING_HUMAN/SUCCEEDED/PARTIAL/FAILED/CANCELLED',
    epoch            INT          NOT NULL DEFAULT 0 COMMENT '租约代次，提交时校验',
    lease_owner      VARCHAR(64)  NULL,
    lease_expires_at DATETIME     NULL,
    attempt          INT          NOT NULL DEFAULT 0,
    summary          VARCHAR(1000) NULL,
    started_at       DATETIME     NULL,
    finished_at      DATETIME     NULL,
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    UNIQUE KEY uk_run_biz (kind, sop_code, project_key, owner_user_id, biz_date),
    KEY idx_run_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '一次流程实例（夜间批等）';

CREATE TABLE IF NOT EXISTS ai_run_node (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id              BIGINT       NOT NULL,
    node_key            VARCHAR(64)  NOT NULL COMMENT 'SOP 内节点标识',
    seq                 INT          NOT NULL,
    executor_kind       VARCHAR(16)  NOT NULL COMMENT 'sidecar/dsh-acp/cli',
    status              VARCHAR(16)  NOT NULL COMMENT 'PENDING/READY/RUNNING/SUCCEEDED/FAILED/WAITING_GATE/UNKNOWN',
    attempt             INT          NOT NULL DEFAULT 0,
    max_attempt         INT          NOT NULL DEFAULT 2,
    rework_count        INT          NOT NULL DEFAULT 0,
    epoch               INT          NOT NULL DEFAULT 0,
    deps                VARCHAR(300) NULL COMMENT 'JSON 数组：前驱 node_key',
    gate_required       TINYINT      NOT NULL DEFAULT 0,
    executor_session_id VARCHAR(120) NULL COMMENT '执行器会话号，用于续跑',
    input_json          JSON         NULL,
    output_json         JSON         NULL,
    error_code          VARCHAR(64)  NULL,
    error_message       VARCHAR(1000) NULL,
    started_at          DATETIME     NULL,
    finished_at         DATETIME     NULL,
    UNIQUE KEY uk_node (run_id, node_key),
    KEY idx_node_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'SOP 节点执行状态';

CREATE TABLE IF NOT EXISTS ai_invocation (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id             BIGINT       NOT NULL,
    node_id            BIGINT       NOT NULL,
    attempt            INT          NOT NULL,
    call_id            VARCHAR(64)  NOT NULL COMMENT '执行器给出的工具调用号',
    logical_action_key VARCHAR(120) NOT NULL COMMENT '幂等键：hash(工具+规范化参数+业务对象)',
    tool_name          VARCHAR(64)  NOT NULL,
    args_json          JSON         NOT NULL,
    status             VARCHAR(16)  NOT NULL COMMENT 'STARTED/SUCCEEDED/FAILED/UNKNOWN/REPLAYED',
    result_json        JSON         NULL,
    error_code         VARCHAR(64)  NULL,
    started_at         DATETIME     NOT NULL COMMENT '前置写入时间，必须早于任何副作用',
    finished_at        DATETIME     NULL,
    replayed_from      BIGINT       NULL COMMENT '回放来源 invocation id',
    approval_json      JSON         NULL COMMENT 'per-call 审批记录',
    UNIQUE KEY uk_inv_call (run_id, node_id, attempt, call_id),
    UNIQUE KEY uk_inv_action (run_id, logical_action_key),
    KEY idx_inv_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工具调用前置记录与幂等键';
