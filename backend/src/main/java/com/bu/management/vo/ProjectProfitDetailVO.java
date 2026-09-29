package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 差额明细（待分配下钻）：营收 = 镜像（财报未税） vs OA 已交付合同逐条（含税÷(1+税率) 换算未税）；
 * 工时/人工成本 = 镜像（total_hours/labor_cost_1） vs 工时系统成本分析逐条（revenue_cost_entry）。
 * 差额 = 镜像 − 明细合计 − 手动分配（与报表差额行一致）。
 */
@Data
public class ProjectProfitDetailVO {

    private String yearMonth;
    private Long businessLineId;
    private String businessLineName;
    /** revenue | labor */
    private String type;

    // ---- revenue ----
    /** 镜像营收（财报未税） */
    private BigDecimal mirrorRevenue;
    /** OA 已交付未税合计（计入本线项目行的合同） */
    private BigDecimal oaDeliveredExTax;
    /** 本线 revenue 手动分配合计 */
    private BigDecimal revenueAllocated;
    /** 差额 = 镜像 − OA 已交付 − 手动分配（= 报表差额行.营业收入） */
    private BigDecimal revenueGap;
    private List<RevenueRow> revenueRows;

    // ---- labor ----
    /** 镜像工时（人月） */
    private BigDecimal mirrorHours;
    /** 镜像人工成本（元） */
    private BigDecimal mirrorLaborCost;
    /** 本线 hours / cost 手动分配合计 */
    private BigDecimal hoursAllocated;
    private BigDecimal costAllocated;
    private List<LaborRow> laborRows;

    @Data
    public static class RevenueRow {
        private String contractNo;
        private String contractName;
        private String customer;
        private LocalDate deliveryDate;
        /** 应收金额（含税） */
        private BigDecimal receivableAmount;
        /** 折算未税（按计入行所在业务线税率） */
        private BigDecimal exTaxAmount;
        /** OA 合同上的业务线 */
        private Long contractBizLineId;
        private String contractBizLineName;
        /** 归并后的根项目及其所在业务线 */
        private Long rootProjectId;
        private String rootProjectName;
        private Long rootLineId;
        private String rootLineName;
        /** 是否计入本线项目行（根项目属于本线）；false=记到别线/无法归桶，属于差额来源线索 */
        private Boolean counted;
    }

    @Data
    public static class LaborRow {
        /** project | sales */
        private String workType;
        private String salesKind;
        private String projectNameRaw;
        private Long projectId;
        /** 归并后的根项目名；project_id 为空为「业务线（其他事项）」 */
        private String rootProjectName;
        private Integer employeeCount;
        /** 工时（人月） */
        private BigDecimal hours;
        /** 成本（元） */
        private BigDecimal costAmount;
    }
}
