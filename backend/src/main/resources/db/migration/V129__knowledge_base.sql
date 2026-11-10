-- 合拍知识库（第一步：只用 MySQL 8 ngram 全文检索，不引向量库 / 不引新依赖）
-- 检索口径：ngram_token_size=2，所以全文索引必须显式 WITH PARSER ngram，否则中文按整段分词永远召回不到。
-- 权限口径：COMPANY 全员可见；OWNER 只有本人可见；PROJECT / BUSINESS_LINE 需要调用方显式传 scopeId 才放行（保守默认）。

-- 文档：一次入库一行，checksum 用于增量跳过（同一来源内容没变就不重建切片）。
CREATE TABLE IF NOT EXISTS kb_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(300) NOT NULL COMMENT '文档标题',
    source_type VARCHAR(20) NOT NULL COMMENT '来源类型：UPLOAD/YUQUE/CASE/MANUAL',
    source_ref VARCHAR(500) NULL COMMENT '来源内唯一标识（文件 sha / 语雀文档 ID / 案例编号），空表示无幂等键',
    scope_type VARCHAR(20) NOT NULL DEFAULT 'COMPANY' COMMENT '可见范围：COMPANY/BUSINESS_LINE/PROJECT/OWNER',
    scope_id BIGINT NULL COMMENT '范围对象 ID：业务线 ID / 项目 ID / 用户 ID',
    business_line VARCHAR(100) NULL COMMENT '所属业务线名称（展示与筛选用，不参与鉴权）',
    tags VARCHAR(500) NULL COMMENT '逗号分隔标签，纯展示',
    checksum VARCHAR(64) NULL COMMENT '内容 sha256，用于增量跳过',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1 = 参与检索，0 = 下线但留档',
    created_by BIGINT NULL COMMENT '入库人用户 ID',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '最后更新时间',
    UNIQUE KEY uk_kb_doc_source (source_type, source_ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
    COMMENT = '知识库文档';

-- 切片：检索的最小单位。heading 与 content 同在一个 ngram 全文索引里，
-- 这样「小节标题」也能被召回（用户常直接搜功能名，功能名往往只出现在标题行）。
CREATE TABLE IF NOT EXISTS kb_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL COMMENT '所属文档 ID',
    ordinal INT NOT NULL COMMENT '块序号，从 0 递增，用于还原原文顺序',
    heading VARCHAR(300) NULL COMMENT '所属小节标题（最近的 # 行 / 「一、」行）',
    content TEXT NOT NULL COMMENT '切片正文',
    char_len INT NOT NULL COMMENT '正文字符数，用于排查超长块',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    KEY idx_kb_chunk_doc (document_id),
    FULLTEXT KEY ft_kb_chunk_content (heading, content) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
    COMMENT = '知识库切片';
