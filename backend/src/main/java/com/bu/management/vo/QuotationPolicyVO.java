package com.bu.management.vo;

import com.bu.management.entity.QuotationPolicyItem;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class QuotationPolicyVO {
    private Long id;
    private String name;
    private String type;
    private String taxMode;
    private Integer version;
    private String status;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<QuotationPolicyItem> items;
}