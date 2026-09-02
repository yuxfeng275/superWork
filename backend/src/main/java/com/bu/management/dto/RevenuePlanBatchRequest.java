package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 预估交付计划批量创建请求：项目×月份。
 * 金额单位元；laborCostYuan 由后端按单价快照计算。
 */
@Data
public class RevenuePlanBatchRequest {

    private Long businessLineId;
    /** NULL=业务线聚合行（会员通项目集） */
    private Long projectId;
    private Integer year;
    private List<Row> rows;

    @Data
    public static class Row {
        /** YYYY-MM */
        private String yearMonth;
        /** 预估交付金额（元） */
        private BigDecimal amountYuan;
        /** 预估人月（可空，空则不计算预估工时成本） */
        private BigDecimal personMonths;
        private String note;
    }
}
