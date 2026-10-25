-- ====================================
-- V114: 存量报价单按新定价规则重算
-- 背景：V109 之前运维服务单价为空，"首年运维免费" 靠 discountRate=0 的隐式约定实现，
-- 而 calculateQuotationTotals 又要求 section='运维服务' 才切分首年/次年，
-- 结果历史私有化报价单的首年、次年运维金额均为 0。
-- 本迁移把首年免费行的既有约定数据化（is_first_year_free=1），
-- 金额重算交给 QuotationRepriceService（需人工/接口触发，不在迁移里改金额）。
-- ====================================

-- 1) 历史上靠 discountRate = 0 表达"首年免费"的行，显式标记出来
UPDATE quotation_line_item
SET is_first_year_free = 1
WHERE (is_first_year_free IS NULL OR is_first_year_free = 0)
  AND discount_rate IS NOT NULL
  AND discount_rate = 0
  AND section = '运维服务';

-- 2) 这些行的折扣复位为 1，金额改由定价引擎按"首年免费 + 基数比例"重算
UPDATE quotation_line_item
SET discount_rate = 1
WHERE is_first_year_free = 1
  AND discount_rate = 0;

-- 3) 补齐 section_code，让导出的分组按编码稳定排序
UPDATE quotation_line_item
SET section_code = UPPER(REPLACE(REPLACE(REPLACE(section, ' ', ''), '_', ''), '-', ''))
WHERE section_code IS NULL;
