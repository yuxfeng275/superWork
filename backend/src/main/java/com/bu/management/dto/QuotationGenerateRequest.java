package com.bu.management.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class QuotationGenerateRequest {
    private Long policyId;
    private Long templateId;
    private Long opportunityId;
    private String variantName;
    private String customerName;
    private String contactPerson;
    private String contactPhone;
    private String contactEmail;
    private String deliveryPeriod;
    private LocalDate quoteDate;
    private Integer validityDays;
    private List<LineItemOverride> lineItemOverrides;

    @Data
    public static class LineItemOverride {
        private Long policyItemId;
        private Long lineItemId;
        private Boolean isSelected;
        private BigDecimal quantity;
        private BigDecimal discountRate;
        /** 手工覆盖的未税单价，覆盖时必须填 priceOverrideReason。 */
        private BigDecimal unitPriceExTax;
        private BigDecimal costPriceExTax;
        private BigDecimal taxRate;
        private String priceOverrideReason;
        private Boolean isCustom;
        private Boolean isFirstYearFree;
        private Boolean remove;
        private String section;
        private String itemName;
        private String description;
        private Integer sortOrder;
    }
}
