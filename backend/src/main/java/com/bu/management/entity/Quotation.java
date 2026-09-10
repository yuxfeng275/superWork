package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("quotation")
public class Quotation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String quotationNo;
    private Long policyId;
    private Long opportunityId;
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
}