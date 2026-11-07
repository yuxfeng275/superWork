-- ====================================
-- V118: 项目智能体闭环 M1 —— 制品与评审队列
-- 设计见 docs/project-agent-loop-plan.md §五 5.3。
-- 1) ai_artifact：夜间 agent 的产出（方案/补丁/修复说明/审查意见处理/清单）先落草稿，
--    白天人只做评审；DRAFT/IN_REVIEW/APPROVED/REJECTED/APPLIED 五态，状态只由本表变更。
-- 2) evidence 存 project_fact.id 列表：无证据不许进评审队列（无证据不派活的下半句）。
-- 3) APPROVED → APPLIED 时回写一条 project_fact（source_type=ARTIFACT），采纳即新事实。
-- 不落任务状态：autoloop 任务号仅存 task_id 作回溯，唯一状态机仍在 agent-autoloop。
-- ====================================

CREATE TABLE IF NOT EXISTS ai_artifact (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id    BIGINT       NOT NULL,
    owner_user_id BIGINT       NOT NULL COMMENT '产出者（夜间运行注册的身份）',
    task_id       VARCHAR(64)  NULL COMMENT 'agent-autoloop 任务号，回溯用',
    kind          VARCHAR(24)  NOT NULL COMMENT 'PLAN/PATCH/FIX_NOTE/REVIEW_FIX/CHECKLIST',
    title         VARCHAR(300) NOT NULL,
    content       MEDIUMTEXT   NULL COMMENT '方案正文 / 补丁说明 / diff 摘要',
    evidence      TEXT         NULL COMMENT 'JSON：project_fact.id 列表，无证据不允许进评审',
    repo_id       BIGINT       NULL COMMENT '代码类制品绑定的仓库',
    branch        VARCHAR(120) NULL COMMENT '夜间产出分支，永不直接改主分支',
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/IN_REVIEW/APPROVED/REJECTED/APPLIED',
    reject_reason VARCHAR(500) NULL COMMENT '驳回原因（REJECTED 必填）',
    reviewer_id   BIGINT       NULL,
    decided_at    DATETIME     NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    KEY idx_artifact_project (project_id, status),
    KEY idx_artifact_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 制品与评审队列';
