-- ====================================
-- V123: 商机跟进闭环（结构化下次跟进时间 + 问句台账）
-- 说明：
--   1) 只做一次性的普通 ALTER/UPDATE/CREATE（Flyway 保证单次执行），不写 information_schema 动态 SQL；
--   2) owner_user_id 回填只做「姓名唯一匹配」，匹配不上留 NULL —— 不猜、不给默认值；
--   3) 旧的 next_follow_up 文本字段保留，前端筛选优先用 next_follow_up_at，文本作兜底。
-- ====================================

-- 1) 商机表新增结构化跟进字段
ALTER TABLE sales_opportunity
    ADD COLUMN next_follow_up_at DATETIME NULL,
    ADD COLUMN follow_up_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN last_follow_up_at DATETIME NULL,
    ADD COLUMN owner_user_id BIGINT NULL,
    ADD COLUMN remind_muted TINYINT NOT NULL DEFAULT 0,
    ADD INDEX idx_so_next_follow (next_follow_up_at),
    ADD INDEX idx_so_owner_user (owner_user_id);

-- 2) 回填最后跟进时间：取该商机所有跟进记录里最大的一次
UPDATE sales_opportunity o
    JOIN (SELECT opportunity_id, MAX(follow_up_at) mx
          FROM sales_opportunity_follow_up
          GROUP BY opportunity_id) t
    ON t.opportunity_id = o.id
SET o.last_follow_up_at = t.mx;

-- 3) 回填负责人用户 ID：只按「姓名唯一匹配」写入，匹配不上保持 NULL
UPDATE sales_opportunity o
SET o.owner_user_id = (SELECT u.id FROM `user` u WHERE u.real_name = o.owner ORDER BY u.id LIMIT 1)
WHERE o.owner IS NOT NULL
  AND TRIM(o.owner) <> ''
  AND (SELECT COUNT(*) FROM `user` u2 WHERE u2.real_name = o.owner) = 1;

-- 4) 商机跟进问句台账
CREATE TABLE opportunity_followup_prompt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    opportunity_id BIGINT NOT NULL,
    target_user_id BIGINT NULL,
    wecom_userid VARCHAR(64) NULL,
    remind_stage VARCHAR(16) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    question_text VARCHAR(500) NOT NULL,
    sent_at DATETIME NOT NULL,
    replied_at DATETIME NULL,
    reply_text VARCHAR(2000) NULL,
    parsed_json TEXT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'SENT',
    dedupe_key VARCHAR(120) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_opp_prompt_dedupe (dedupe_key),
    KEY idx_opp_prompt_opp (opportunity_id),
    KEY idx_opp_prompt_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商机跟进问句台账';

-- 5) 跟进记录补来源与问句溯源
ALTER TABLE sales_opportunity_follow_up
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN prompt_id BIGINT NULL;
