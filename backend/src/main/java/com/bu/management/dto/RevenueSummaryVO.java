package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class RevenueSummaryVO {
    private Integer year;
    private Long h1Receivable;
    private Long h2Receivable;
    private BigDecimal h1Hours;
    private BigDecimal h2Hours;
    private Long h1DeliveryCost;
    private Long h2DeliveryCost;
    private Long h2Estimate;
    private Long partnerCost;
    private Long serverCost;
    private Long otherCost;
    private Long totalCost;
    private Long profit;
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
        private Long h1Receivable;
        private Long h2Receivable;
        private BigDecimal h1Hours;
        private BigDecimal h2Hours;
        private Long h1DeliveryCost;
        private Long h2DeliveryCost;
        private Long h2Estimate;
        private Long partnerCost;
        private Long serverCost;
        private Long otherCost;
        private Long totalCost;
        private Long profit;
        private BigDecimal profitRate;
        private List<ProjectSummary> projects;
        private List<MonthlyData> months;
    }

    @Data
    public static class ProjectSummary {
        private Long projectId;
        private String projectName;
        private Long h1Receivable;
        private Long h2Receivable;
        private BigDecimal h1Hours;
        private BigDecimal h2Hours;
        private Long h1DeliveryCost;
        private Long h2DeliveryCost;
        private Long h2Estimate;
        private Long partnerCost;
        private Long serverCost;
        private Long otherCost;
        private Long totalCost;
        private Long profit;
        private BigDecimal profitRate;
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
