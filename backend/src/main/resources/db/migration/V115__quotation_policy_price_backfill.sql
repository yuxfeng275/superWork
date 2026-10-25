-- ====================================
-- V115: 存量报价策略补真实价格（生产库已有 6 条策略 + 228 条明细，播种脚本会跳过）
-- 背景：QuotationPolicyDataInitializer 在 policy 表非空时直接 return，所以 V109 引入的
-- PERCENT_OF_BASE / firstYearFree / costPrice / minDiscountRate 只对新库生效，
-- 生产库里运维服务仍是 unit_price = NULL、定制开发也是 NULL，生成报价依旧会把
-- 运维费算成 0。这里按 itemKey 把价格规则补到存量数据上，幂等可重跑。
-- ====================================

-- 1) 运维服务：改为"产品模块总价 × 费率 / 年"，基础运维首年赠送（只计入次年及以后）
UPDATE quotation_policy_item
SET charge_method = 'PERCENT_OF_BASE',
    charge_base_section = '产品模块',
    category = '产品模块',
    charge_rate = 0.2000,
    first_year_free = 1,
    unit_price = NULL,
    charge_unit = '年'
WHERE item_key = 'OPS-BASIC';

UPDATE quotation_policy_item
SET charge_method = 'PERCENT_OF_BASE',
    charge_base_section = '产品模块',
    category = '产品模块',
    charge_rate = 0.0800,
    first_year_free = 0,
    unit_price = NULL,
    charge_unit = '年'
WHERE item_key = 'OPS-UPGRADE';

-- 2) 定制开发：区间报价落基准单价（区间中位数）+ 55% 成本价，避免生成 0 元报价
UPDATE quotation_policy_item SET unit_price = 60000,  cost_price = 33000 WHERE item_key IN ('CUSTOM-BI', 'PRIV-CUSTOM-BI');
UPDATE quotation_policy_item SET unit_price = 50000,  cost_price = 27500 WHERE item_key IN ('CUSTOM-TAG', 'PRIV-CUSTOM-TAG');
UPDATE quotation_policy_item SET unit_price = 90000,  cost_price = 49500 WHERE item_key IN ('CUSTOM-FUNC', 'PRIV-CUSTOM-FUNC');
UPDATE quotation_policy_item SET unit_price = 125000, cost_price = 68750 WHERE item_key IN ('CUSTOM-MKT', 'PRIV-CUSTOM-MKT');

-- 3) 收费方式统一成定价引擎能识别的枚举
UPDATE quotation_policy_item
SET charge_method = 'PER_UNIT'
WHERE charge_method IN ('按次收取', '按模块收取', '按店收取')
  AND item_key NOT IN ('OPS-BASIC', 'OPS-UPGRADE');

UPDATE quotation_policy_item
SET charge_method = 'FIXED'
WHERE unit_price IS NOT NULL
  AND (charge_method IS NULL OR charge_method = '' OR charge_method NOT IN ('FIXED', 'PER_UNIT', 'PERCENT_OF_BASE'));

-- 4) 策略层：绑定同类型模板 + 底价折扣 + 强制折扣审批
UPDATE quotation_policy SET min_discount_rate = 0.85 WHERE min_discount_rate IS NULL;

UPDATE quotation_policy p
JOIN quotation_template t ON t.type = p.type AND t.status = 'PUBLISHED'
SET p.template_id = t.id
WHERE p.template_id IS NULL;
