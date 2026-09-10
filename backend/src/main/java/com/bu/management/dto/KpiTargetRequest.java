package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class KpiTargetRequest {
    private Integer year;
    private String reportGroup;
    private BigDecimal revenueTarget;
    private BigDecimal profitTarget;
    private String remark;
}
