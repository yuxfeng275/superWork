package com.bu.management.vo;

import com.bu.management.entity.QuotationBrandScope;
import com.bu.management.entity.QuotationLineItem;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class QuotationVO {
    private Long id;
    private String quotationNo;
    private Long policyId;
    private Long opportunityId;
    private String opportunityName;
    private String taxMode;
    private String customerName;
    private String contactPerson;
    private String contactPhone;
    private String contactEmail;
    private String deliveryPeriod;
    private LocalDate quoteDate;
    private Integer validityDays;
    private String currency;
    private String invoiceType;
    private BigDecimal firstYearTotalExTax;
    private BigDecimal firstYearTotalInclTax;
    private BigDecimal subsequentYearTotalExTax;
    private BigDecimal subsequentYearTotalInclTax;
    private String quotationNote;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<QuotationLineItem> lineItems;
    private List<QuotationBrandScope> brandScopes;
}