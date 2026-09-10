package com.bu.management.dto;

import lombok.Data;
import java.time.LocalDate;

@Data
public class QuotationPolicyRequest {
    private String name;
    private String type;
    private String taxMode;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
}