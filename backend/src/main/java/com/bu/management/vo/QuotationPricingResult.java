package com.bu.management.vo;

import com.bu.management.entity.QuotationLineItem;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 报价试算/重算的统一结果。前后端都只认这一份算法，避免前端自行累加导致金额分叉。
 */
@Data
public class QuotationPricingResult {
    /** 明细行（已计算 subtotal，未选中行的 subtotal 为 null）。 */
    private List<QuotationLineItem> lineItems;
    private BigDecimal firstYearTotalExTax;
    private BigDecimal firstYearTotalInclTax;
    private BigDecimal subsequentYearTotalExTax;
    private BigDecimal subsequentYearTotalInclTax;
    /** 首年未税毛利额，成本价缺失时为 null。 */
    private BigDecimal grossProfitExTax;
    /** 首年毛利率，成本价缺失时为 null。 */
    private BigDecimal grossMarginRate;
    /** 实际生效的最低综合折扣（1 表示未打折）。 */
    private BigDecimal effectiveDiscount;
    /** 策略底价折扣，低于它需要走审批。 */
    private BigDecimal floorDiscount;
    /** 是否触发了底价/必选等规则。 */
    private boolean discountOverride;
    private List<String> violations = new ArrayList<>();
}
