package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目利润表：月份块 × 业务线 × 行（项目/销售/差额/合计）。
 * 表头固定 15 列：月份 | 业务线 | 分类 | 项目 | 营业收入 | 短信成本 | 直接成本 | 平台佣金&手续费 |
 * 赔付 | 协力&外包 | 软件赠送 | 工时 | 成本 | 考核毛利 | 考核毛利率(%)。
 * <p>口径：合计行取工时系统业务线利润镜像（biz_line_profit_report，未税）；full 线项目行营收取
 * OA 合同已交付金额（含税），按业务线税率 ÷(1+taxRate/100) 换算为未税后与镜像同口径；
 * 差额行 = 合计 − Σ全部明细行（固定口径，不随筛选变化）。</p>
 */
@Data
public class ProjectProfitReportVO {

    private Integer year;
    /** 镜像已同步月份 YYYY-MM 升序（报表月份轴只由镜像驱动） */
    private List<String> availableMonths;
    /** 镜像行最大 synced_at */
    private LocalDateTime lastSyncedAt;
    /** 筛选项（不受当前过滤影响） */
    private List<LineOption> lineOptions;
    private List<Block> blocks;

    @Data
    public static class LineOption {
        private Long businessLineId;
        private String businessLineName;
        private List<ProjectOption> projects;
    }

    @Data
    public static class ProjectOption {
        private Long projectId;
        private String projectName;
    }

    @Data
    public static class Block {
        /** '2026-01' | 'H1' | 'H2' | 'YEAR' */
        private String key;
        /** '1月' | 'H1' | 'H2' | '全年' */
        private String label;
        private List<Line> lines;
        /** 跨业务线汇总行（rowType=SUMMARY）：Σ 当前块内各业务线合计行（镜像值求和），毛利/率按公式重算；
         *  随 businessLineIds 过滤联动（只汇总块内返回的业务线）；块内无业务线时为 null */
        private Row summary;
    }

    @Data
    public static class Line {
        private Long businessLineId;
        private String businessLineName;
        private String revenueMode;
        private List<Row> rows;
        private Row total;
        private Row residual;
    }

    @Data
    public static class Row {
        /** PROJECT | SALES | LINE_OTHER | TOTAL | RESIDUAL | SUMMARY */
        private String rowType;
        /** '项目' | '销售' | '差额'（TOTAL/SUMMARY 为 null） */
        private String category;
        private Long projectId;
        private String projectName;
        /** editable=true 仅 full 线真实项目行 */
        private Boolean editable;
        private BigDecimal revenue;
        private BigDecimal smsCost;
        private BigDecimal directCost;
        private BigDecimal platformFee;
        private BigDecimal compensation;
        private BigDecimal outsourcing;
        private BigDecimal softwareGift;
        /** 工时（人月） */
        private BigDecimal hours;
        /** 人工成本（元） */
        private BigDecimal cost;
        private BigDecimal grossProfit;
        private BigDecimal grossProfitRate;
    }
}
