-- ====================================
-- V124: 方案 B —— 商机负责人姓名 → 企微 userid 映射
-- 说明：
--   1) 现网 18 个系统账号里没有商机负责人，sales_opportunity.owner_user_id 恒为 NULL，
--      按 user id 解析企微 userid 的路走不通；这张表直接按「负责人姓名」对齐，
--      不改账号体系，不往 `user` 表塞假账号。
--   2) 姓名与 sales_opportunity.owner 文本对齐（owner_name 唯一），enabled=0 的行保留但不参与发送解析。
--   3) source 记录来源：MANUAL=人工维护，CALENDAR=日程参会人推导，SESSION=机器人会话推导。
-- ====================================

CREATE TABLE opportunity_owner_wecom (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_name VARCHAR(100) NOT NULL COMMENT '商机负责人姓名（与 sales_opportunity.owner 对齐）',
    wecom_userid VARCHAR(128) NOT NULL COMMENT '企业微信 userid',
    enabled TINYINT NOT NULL DEFAULT 1,
    source VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/CALENDAR/SESSION',
    remark VARCHAR(200) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_owner_name (owner_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商机负责人企微映射（方案 B）';
