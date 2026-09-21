-- ====================================
-- V86: AI 助手会话附件（本地落盘，随消息注入文本摘要）
-- ====================================

CREATE TABLE IF NOT EXISTS ai_agent_attachment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL COMMENT '归属会话',
  owner_user_id BIGINT NOT NULL COMMENT '上传人',
  file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
  content_type VARCHAR(128) NULL,
  size_bytes BIGINT NOT NULL,
  sha256 CHAR(64) NOT NULL,
  relative_path VARCHAR(512) NOT NULL COMMENT '存储相对路径（会话子目录下）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ai_attachment_session (session_id),
  INDEX idx_ai_attachment_owner (owner_user_id),
  CONSTRAINT fk_ai_attachment_session FOREIGN KEY (session_id)
    REFERENCES ai_agent_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 助手会话附件';
