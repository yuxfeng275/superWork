package com.bu.management.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class QuotationListVO {
    private Long id;
    private String quotationNo;
    private String customerName;
    private BigDecimal firstYearTotalInclTax;
    private BigDecimal firstYearTotalExTax;
    private String status;
    private LocalDate quoteDate;
    private LocalDate expireAt;
    private Boolean dueSoon;
    private Integer validityDays;
    private Long opportunityId;
    private String opportunityName;
    private Long variantId;
    private String variantName;
    private Integer versionNo;
    private Integer isPreferred;
    private BigDecimal grossMarginRate;
    private Integer discountOverride;
    private List<String> availableTransitions;
}
