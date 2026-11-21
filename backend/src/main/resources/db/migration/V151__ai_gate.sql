-- ====================================
-- V151: 人工闸门
-- 设计见 docs/ai-night-cycle/03-functions.md §3.2（ai_gate）。
-- 1) 每次提交都带「请求时的节点状态 + attempt + rework」，用于乐观校验：
--    重复提交或过期提交在这里被拒，而不是靠前端按钮禁用。
-- 2) decision 制品通过 ai_artifact(kind=DECISION) 落库，闸门的证据就是上游制品与事实。
-- ====================================

CREATE TABLE IF NOT EXISTS ai_gate (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id                 BIGINT       NOT NULL,
    node_id                BIGINT       NOT NULL,
    status                 VARCHAR(16)  NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/APPROVED_RETRY/EXPIRED',
    assignee_user_id       BIGINT       NOT NULL COMMENT '该由谁来批',
    requested_from_status  VARCHAR(16)  NOT NULL COMMENT '提交时的节点状态（乐观校验）',
    requested_attempt      INT          NOT NULL,
    requested_rework       INT          NOT NULL,
    question               VARCHAR(500) NULL,
    decision               VARCHAR(1000) NULL COMMENT '通过理由 / 打回原因',
    reject_to              VARCHAR(64)  NULL COMMENT '打回目标 node_key',
    artifact_id            BIGINT       NULL COMMENT 'decision 制品',
    decided_by             BIGINT       NULL,
    decided_at             DATETIME     NULL,
    due_at                 DATETIME     NULL,
    created_at             DATETIME     NOT NULL,
    KEY idx_gate_run (run_id, node_id),
    KEY idx_gate_assignee (assignee_user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '人工闸门';
