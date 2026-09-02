package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目交付营收（利润）汇总 —— 业务线 × 项目。
 * 金额单位：元（展示万由前端换算）；工时单位：人月。
 * h1/h2/ytd 为三个时窗副本（1-6 月 / 7-12 月 / 全年）。
 *
 * <p>毛利口径（含预估开关 includeEstimate）：
 * <ul>
 *   <li>关闭：营收只用实际已交付，成本只用实际项目工时成本；</li>
 *   <li>开启：营收=已交付+预估交付，成本=实际项目工时成本+预估交付关联预估工时成本。</li>
 *   <li>历史字段 laborProfit/grossProfit：项目行毛利不减任何销售成本；
 *       业务线 totals 毛利=项目毛利合计−该线全部销售工时成本（含已分配+未分配）。</li>
 * </ul>
 *
 * <p>销售成本拆分（成单销售工时/成本 → 项目层，只落有明确成单证据的行，不做均摊）：
 * <ul>
 *   <li>项目行 allocatedSales*：已分配到该项目的销售工时/成本（销售行有明确成单项目证据）；</li>
 *   <li>业务线/整表 unallocatedSales*：无法确认成单归属、保留在业务线池的销售工时/成本，原因见 salesUnallocatedDetail；</li>
 *   <li>项目真实利润 trueProfit = 营收 − 项目工时成本(±预估) − 已分配销售成本 − 其他成本（销售未分配不从项目重复扣）；</li>
 *   <li>业务线 totals.trueProfit = Σ项目真实利润 − 未分配销售成本（已分配项已在项目行内扣除）；
 *       数值上等于毛利（销售成本全额在业务线层计入），但项目行新增 trueProfit 区分口径。</li>
 *   <li>simple 模式业务线（产品/精准等）销售行并入单行项目工时，不做销售拆分（成本不可再分）。</li>
 * </ul>
 */
@Data
public class RevenueDeliverySummaryVO {

    private Integer year;
    private Boolean includeEstimate;
    private List<Line> lines = new ArrayList<>();
    private Overview overview;

    @Data
    public static class Line {
        private Long businessLineId;
        private String businessLineName;
        /** 该线全年销售工时合计（完结月实际，人月） */
        private BigDecimal salesHours;
        /** 该线全年销售工时成本（完结月实际，元） */
        private BigDecimal salesCost;
        /** 该线全年已分配（成单→项目）销售工时（人月） */
        private BigDecimal salesAllocatedHours;
        /** 该线全年已分配销售工时成本（元） */
        private BigDecimal salesAllocatedCost;
        /** 该线全年未分配销售工时（人月） */
        private BigDecimal salesUnallocatedHours;
        /** 该线全年未分配销售工时成本（元） */
        private BigDecimal salesUnallocatedCost;
        /** 未分配销售成本原因明细（按原因代码汇总，全年） */
        private List<UnallocatedItem> salesUnallocatedDetail = new ArrayList<>();
        private List<ProjectRow> projects = new ArrayList<>();
        /** 业务线汇总（毛利=项目毛利合计−该线全部销售成本；trueProfit=Σ项目真实利润−未分配销售成本） */
        private ProjectRow totals;
    }

    @Data
    public static class ProjectRow {
        /** NULL=业务线聚合行（会员通项目集 / simple 线行） */
        private Long projectId;
        private String name;
        /** true=业务线聚合行 */
        private Boolean isAggregate;
        /** 全年 OA 合同总额（元，按收款销售年份口径） */
        private BigDecimal oaContract;
        private Window h1 = new Window();
        private Window h2 = new Window();
        private Window ytd = new Window();
    }

    @Data
    public static class Window {
        /** 已交付金额（元，交付日期<=今天） */
        private BigDecimal delivered;
        /** 预估交付金额（元） */
        private BigDecimal estimated;
        /** 项目工时合计（人月，完结月实际） */
        private BigDecimal projectHours;
        /** 项目工时成本（元，完结月实际） */
        private BigDecimal projectLaborCost;
        /** 预估交付关联的预估工时成本（元） */
        private BigDecimal estimatedLaborCost;
        /** 销售工时合计（人月；仅业务线级，项目行=0） */
        private BigDecimal salesHours;
        /** 销售工时成本（元；仅业务线级，项目行=0） */
        private BigDecimal salesCost;
        /** 已分配（成单→本行）销售工时（人月） */
        private BigDecimal allocatedSalesHours;
        /** 已分配（成单→本行）销售工时成本（元） */
        private BigDecimal allocatedSalesCost;
        /** 未分配销售工时（人月；项目行=0，仅线/表汇总） */
        private BigDecimal unallocatedSalesHours;
        /** 未分配销售工时成本（元；项目行=0，仅线/表汇总） */
        private BigDecimal unallocatedSalesCost;
        private OtherCosts otherCosts;
        /** 人工成本后利润 = 营收 − 项目人工（含预估）− 销售成本（行级为 0） */
        private BigDecimal laborProfit;
        /** 毛利 = laborProfit − 其他成本（历史口径：项目行不减销售成本；线/表汇总减全部销售成本） */
        private BigDecimal grossProfit;
        /** 毛利率（%）= grossProfit / 对应口径营收 ×100，营收为 0 时为 null */
        private BigDecimal grossRate;
        /** 真实利润：项目行=毛利−已分配销售成本；线/表汇总=毛利（销售全额已计） */
        private BigDecimal trueProfit;
        /** 真实利润率（%），营收为 0 时为 null */
        private BigDecimal trueProfitRate;
    }

    @Data
    public static class OtherCosts {
        private BigDecimal partner;
        private BigDecimal server;
        private BigDecimal other;
        private BigDecimal total;
    }

    @Data
    public static class UnallocatedItem {
        /** 原因代码：NO_OPP_LINK / NO_MATCH_CONTRACT / MULTI_PROJECT / POOL_NO_EVIDENCE */
        private String reason;
        private String label;
        /** 全年金额（元） */
        private BigDecimal cost;
    }

    @Data
    public static class Overview {
        private Boolean includeEstimate;
        /** 全年 OA 合同总额（元） */
        private BigDecimal totalOaContract;
        private BigDecimal totalDelivered;
        private BigDecimal totalEstimated;
        /** 总人工成本（项目工时成本 + 预估工时成本(开) + 销售工时成本） */
        private BigDecimal totalLaborCost;
        /** 已分配销售工时成本合计（元） */
        private BigDecimal totalAllocatedSalesCost;
        /** 未分配销售工时成本合计（元） */
        private BigDecimal totalUnallocatedSalesCost;
        private BigDecimal totalOtherCost;
        private BigDecimal totalProfit;
        private BigDecimal profitRate;
        /** 整表真实利润（= totalProfit，销售成本已全额计入） */
        private BigDecimal totalTrueProfit;
        private BigDecimal trueProfitRate;
        /** 未分配销售成本原因明细（整表全年） */
        private List<UnallocatedItem> salesUnallocatedDetail = new ArrayList<>();
    }
}
