-- V100: 待交付合同确认（项目利润预估营收整合）
-- 待交付 = revenue_contract_entry.delivery_date 为空；预计交付月份 = service_end_date 所在月份
-- （service_end_date 为空归「未定月份」，仅展示标记、不进月份预估块）。
-- 确认后的合同计入项目利润预估块的营收与利润；未确认仅展示（+xx 待确认），不计入利润。
-- 一行对应一条合同明细的当前确认状态：revoked_at IS NULL = 确认生效中；取消确认置 revoked_*；
-- 再次确认复用同一行（uk_entry 唯一键），confirmed_* 覆盖、revoked_* 清空。
CREATE TABLE delivery_confirmation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    contract_entry_id BIGINT NOT NULL COMMENT 'revenue_contract_entry.id（合同明细行）',
    expected_month VARCHAR(7) NULL COMMENT '确认时预计交付月份 YYYY-MM；NULL=服务结束时间缺失（未定月份）',
    confirmed_by BIGINT NULL COMMENT '确认人 sys_user.id',
    confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '确认时间',
    revoked_by BIGINT NULL COMMENT '取消确认人 sys_user.id',
    revoked_at DATETIME NULL COMMENT '取消确认时间；NULL=确认生效中',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_entry (contract_entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待交付合同确认（项目利润预估营收口径）';
