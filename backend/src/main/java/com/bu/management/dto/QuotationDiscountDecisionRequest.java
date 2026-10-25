package com.bu.management.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class QuotationDiscountDecisionRequest {
    private String requestedBy;
    private BigDecimal requestedDiscount;
    private String reason;
    /** 审批结论：APPROVED / REJECTED */
    private String status;
    private String approver;
    private String comment;
}
