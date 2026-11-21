-- ====================================
-- V149: 统一空间 —— 文件元数据表（原文在对象存储）
-- 设计见 docs/ai-night-cycle/03-functions.md §3.1。
-- 1) ai_file 只存元数据；原文由 ObjectStore 保存（prod=MinIO，dev=本地盘）。
-- 2) 同一项目内同一 sha256 只存一份：重复投递复用文件行，事实层按 file:<id> 去重。
-- 3) 与事实层的关联不建中间表：project_fact.source_type=FILE_UPLOAD, source_ref='file:<id>'。
-- ====================================

CREATE TABLE IF NOT EXISTS ai_file (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id    BIGINT       NOT NULL COMMENT '归属项目',
    owner_user_id BIGINT       NOT NULL COMMENT '投递人',
    filename      VARCHAR(300) NOT NULL COMMENT '原始文件名',
    content_type  VARCHAR(120) NULL COMMENT 'MIME',
    size_bytes    BIGINT       NOT NULL COMMENT '字节数',
    sha256        CHAR(64)     NOT NULL COMMENT '内容哈希（去重键）',
    object_key    VARCHAR(300) NOT NULL COMMENT '对象存储键：<projectId>/<sha前2位>/<sha>.<ext>',
    source        VARCHAR(24)  NOT NULL COMMENT 'UPLOAD/EMAIL/SYNC_DIR/AGENT',
    source_ref    VARCHAR(255) NULL COMMENT '邮件 id / 目录路径 / run 号',
    visibility    VARCHAR(16)  NOT NULL DEFAULT 'PROJECT' COMMENT 'PROJECT/PRIVATE_COMPANY/OWNER',
    deleted_at    DATETIME     NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    UNIQUE KEY uk_file_sha (project_id, sha256),
    KEY idx_file_project (project_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '统一空间：文件元数据（原文在对象存储）';
