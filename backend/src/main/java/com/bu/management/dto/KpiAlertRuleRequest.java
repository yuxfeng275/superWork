package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class KpiAlertRuleRequest {
    /** 报表行分组；null/空=全局默认 */
    private String reportGroup;
    private BigDecimal weeklyDivisor;
    private BigDecimal yellowRatio;
    private Integer enabled;
}
