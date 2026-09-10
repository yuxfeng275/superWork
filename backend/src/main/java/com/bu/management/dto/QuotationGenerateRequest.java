package com.bu.management.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class QuotationGenerateRequest {
    private Long policyId;
    private Long opportunityId;
    private String customerName;
    private String contactPerson;
    private String contactPhone;
    private String contactEmail;
    private String deliveryPeriod;
    private List<LineItemOverride> lineItemOverrides;

    @Data
    public static class LineItemOverride {
        private Long policyItemId;
        private Boolean isSelected;
        private BigDecimal quantity;
        private BigDecimal discountRate;
    }
}