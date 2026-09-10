package com.bu.management.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class QuotationPolicyItemRequest {
    private String section;
    private String category;
    private String itemKey;
    private String itemName;
    private String description;
    private String priceDescription;
    private Integer isRequired;
    private BigDecimal unitPrice;
    private BigDecimal taxRate;
    private String chargeMethod;
    private String chargeUnit;
    private String remark;
    private Integer sortOrder;
}