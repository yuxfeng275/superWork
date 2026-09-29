-- V99: 修正 full 线（云鹿Saas/定制）税率 4.07 → 6.00
-- 背景：V54 播种时所有在用业务线均为 6.00，后被手工改为 4.07；经 2026 年镜像逐月验证
-- （定制 2/3/6/7 月、SAAS 2 月的「OA含税 ÷ 镜像未税」隐含税率均为 6.00%），财报换算口径为 6%。
-- 该字段仅项目利润表（ProjectProfitService.exTax）消费，不影响其他模块。
UPDATE business_line SET tax_rate = 6.00
WHERE revenue_mode = 'full' AND tax_rate = 4.07;
