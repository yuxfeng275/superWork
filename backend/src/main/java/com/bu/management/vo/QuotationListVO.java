package com.bu.management.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class QuotationListVO {
    private Long id;
    private String quotationNo;
    private String customerName;
    private BigDecimal firstYearTotalInclTax;
    private String status;
    private LocalDate quoteDate;
    private String opportunityName;
}