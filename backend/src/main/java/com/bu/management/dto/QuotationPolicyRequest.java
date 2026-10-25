package com.bu.management.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class QuotationPolicyRequest {
    private String name;
    private String type;
    private Long templateId;
    private String taxMode;
    private BigDecimal minDiscountRate;
    private Integer approvalRequired;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
}