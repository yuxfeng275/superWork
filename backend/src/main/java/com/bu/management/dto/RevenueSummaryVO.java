package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class RevenueSummaryVO {
    private Integer year;
    private Long totalReceivable;
    private Long totalReceived;
    private Long totalCost;
    private Long totalProfit;
    private BigDecimal profitRate;
    private List<MonthlyTrendItem> monthlyTrend;
    private List<BusinessLineSummary> businessLines;

    @Data
    public static class MonthlyTrendItem {
        private String month;
        private Long income;
        private Long cost;
    }

    @Data
    public static class BusinessLineSummary {
        private Long businessLineId;
        private String businessLineName;
        private String type;
        private Long totalReceivable;
        private Long totalReceived;
        private Long totalCost;
        private Long totalProfit;
        private BigDecimal profitRate;
        private List<ProjectSummary> projects;
        private List<MonthlyData> months;
    }

    @Data
    public static class ProjectSummary {
        private Long projectId;
        private String projectName;
        private Long receivable;
        private Long received;
        private BigDecimal deliveryHours;
        private Long deliveryCost;
        private Long salesCost;
        private Long partnerCost;
        private Long serverCost;
        private Long otherCost;
        private Long totalCost;
        private Long profit;
        private BigDecimal profitRate;
        private Long h2Estimate;
        private List<MonthlyData> months;
    }

    @Data
    public static class MonthlyData {
        private String month;
        private Long income;
        private BigDecimal hours;
        private Long cost;
    }
}
