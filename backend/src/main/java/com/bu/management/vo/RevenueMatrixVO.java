package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 营收矩阵：业务线 → 类型（项目/销售）→ 行 × 12 个月。
 * 金额单位：元；工时单位：人月。完结月份为实际值，未完结月份为预估。
 */
@Data
public class RevenueMatrixVO {

    private Integer year;
    private List<MonthInfo> months = new ArrayList<>();
    private List<LineBlock> lines = new ArrayList<>();
    private List<Cell> monthTotals = new ArrayList<>();
    private Cell grandTotal;
    private Overview overview;

    @Data
    public static class MonthInfo {
        private String yearMonth;
        private boolean closed;
    }

    @Data
    public static class Cell {
        /** 人月 */
        private BigDecimal hours;
        /** 元 */
        private BigDecimal cost;
        /** actual=完结实际 / estimate=预估 / null=无数据 */
        private String source;
        private Integer estimateCount;
    }

    @Data
    public static class Row {
        private String rowKey;
        private String name;
        /** project=具体项目 / line_pool=项目集 / sales_specific=具体销售项目 / pool=商机集合 / other=其他 */
        private String kind;
        private Long projectId;
        private Long salesProjectId;
        private Long opportunityId;
        private String opportunityName;
        /** 累计完结人均成本（元/人月），无完结历史为 null */
        private BigDecimal unitPrice;
        private List<Cell> months = new ArrayList<>();
        private Cell totals;
    }

    @Data
    public static class Section {
        /** project / sales */
        private String type;
        private List<Row> rows = new ArrayList<>();
    }

    @Data
    public static class LineBlock {
        private Long businessLineId;
        private String businessLineName;
        /** full=项目+销售明细行 / aggregate=项目销售两行聚合 / simple=单行汇总 */
        private String mode;
        private List<Section> sections = new ArrayList<>();
        private List<Cell> monthTotals = new ArrayList<>();
        private Cell totals;
    }

    @Data
    public static class Overview {
        private BigDecimal totalHours;
        private BigDecimal projectHours;
        private BigDecimal salesHours;
        private BigDecimal totalCost;
        /** 综合单价（元/人月） */
        private BigDecimal avgUnitPrice;
        private Integer closedMonthCount;
    }
}
