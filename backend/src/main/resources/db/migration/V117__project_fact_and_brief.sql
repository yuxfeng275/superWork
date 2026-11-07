-- ====================================
-- V117: 项目智能体闭环 M0 —— 项目事实层 / 项目仓库绑定 / 每人晨报
-- 设计见 docs/project-agent-loop-plan.md §五。
-- 1) project_fact：项目唯一事实源（每段信息带出处，dedupe_hash 幂等汇入）
-- 2) project_git_repo：项目绑定仓库与 agent 基础设置（夜间代码类执行的前提）
-- 3) ai_brief：每人每日晨报（07:30 分发，一人一条，唯一键防重复）
-- 三张表都只由合拍写入；任务状态不落这里（唯一来源是 agent-autoloop）。
-- ====================================

CREATE TABLE IF NOT EXISTS project_fact (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id  BIGINT       NOT NULL COMMENT '归属项目',
    source_type VARCHAR(32)  NOT NULL COMMENT 'MEETING/EMAIL/WORKITEM/KEY_MATTER/QUOTATION/FILE_UPLOAD/CHAT_EXPORT/GIT',
    source_ref  VARCHAR(255) NOT NULL COMMENT '源主键或 URL，点回证据的唯一入口',
    actor_id    BIGINT       NULL COMMENT '谁产的（内部 userId）',
    happened_at DATETIME     NOT NULL COMMENT '信息发生时间',
    title       VARCHAR(300) NULL,
    body        MEDIUMTEXT   NULL COMMENT '原文（只作资料，不拼进提示词）',
    digest      VARCHAR(1000) NULL COMMENT '供模型消费的摘要',
    visibility  VARCHAR(16)  NOT NULL DEFAULT 'PROJECT' COMMENT 'PROJECT/PRIVATE_COMPANY/OWNER',
    dedupe_hash CHAR(64)     NOT NULL COMMENT 'sha256(source_type|source_ref)，重复汇入走更新',
    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,
    UNIQUE KEY uk_fact_hash (dedupe_hash),
    KEY idx_fact_project (project_id, happened_at),
    KEY idx_fact_source (source_type, source_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目事实层：项目唯一事实源';

CREATE TABLE IF NOT EXISTS project_git_repo (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id     BIGINT       NOT NULL,
    name           VARCHAR(100) NOT NULL COMMENT '展示名',
    repo_url       VARCHAR(500) NOT NULL COMMENT '仓库地址（HTTPS）',
    default_branch VARCHAR(100) NOT NULL DEFAULT 'main',
    work_dir       VARCHAR(300) NULL COMMENT '允许 agent 改动的目录（如 sql/）',
    agent          VARCHAR(32)  NOT NULL DEFAULT 'dsh' COMMENT 'dsh/command/claude-code',
    credential_ref VARCHAR(200) NULL COMMENT '凭据引用（不入库明文，指向 system_config 键）',
    enabled        TINYINT      NOT NULL DEFAULT 1,
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    KEY idx_repo_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目绑定的代码仓库（HTTPS）';

CREATE TABLE IF NOT EXISTS ai_brief (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT   NOT NULL,
    project_id   BIGINT   NOT NULL DEFAULT 0 COMMENT '0 = 跨项目总晨报',
    brief_date   DATE     NOT NULL,
    content      MEDIUMTEXT NOT NULL COMMENT 'JSON 字符串：{items:[{title,why,artifactIds,links}]}',
    source_task  VARCHAR(64) NULL COMMENT 'agent-autoloop 任务号，便于 why 回溯',
    delivered_at DATETIME NULL COMMENT '站内/企微送达时间',
    created_at   DATETIME NOT NULL,
    updated_at   DATETIME NOT NULL,
    UNIQUE KEY uk_brief_user_day (user_id, brief_date, project_id),
    KEY idx_brief_date (brief_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每人每日晨报';
