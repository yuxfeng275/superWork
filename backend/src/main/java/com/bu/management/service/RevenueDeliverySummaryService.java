package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueDeliveryPlan;
import com.bu.management.entity.RevenueOtherCost;
import com.bu.management.entity.RevenueSalesProject;
import com.bu.management.entity.RevenueWorklogEntry;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueDeliveryPlanMapper;
import com.bu.management.mapper.RevenueOtherCostMapper;
import com.bu.management.mapper.RevenueSalesProjectMapper;
import com.bu.management.mapper.RevenueWorklogEntryMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.bu.management.vo.RevenueDeliverySummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目交付营收（利润）汇总。
 * <p>口径（与业务确认）：
 * <ul>
 *   <li>OA 合同总额/已交付均取合同明细「应收金额」：总额=该项目全部行之和；已交付=交付日期&lt;=今天行之和；
 *       H1/H2 按交付日期所在月份 1-6/7-12 划分，跨年忽略（按收款销售月份 year 视图隔离）。</li>
 *   <li>毛利 = (已交付+预估交付) − 人工成本 − 其他成本；人工成本=项目工时成本+销售工时成本（销售仅业务线级）。</li>
 *   <li>项目工时合计/成本沿用矩阵口径：仅完结月实际（未完结月不计）；预估交付计划与月份完结解耦照常全算。</li>
 *   <li>行集合与既有营收矩阵 full 模式一致（主项目行、佳贝/海普归澳优），会员通按业务线聚合行「项目集」。</li>
 *   <li>其他成本按 月份×业务线×项目×类型 手动维护，仅取归属月 &lt;= 当月（实际发生）部分。</li>
 * </ul>
 *
 * <p>销售成本 → 项目层拆分（成单证据制，确定性规则，不做均摊）：
 * <ul>
 *   <li>仅 full/aggregate 模式业务线参与拆分；simple 模式（产品/精准等）销售行并入单行项目工时，不做拆分。</li>
 *   <li>可分配证据：sales_kind='specific' 的销售成本行 → revenue_sales_project(商机绑定) →
 *       商机客户在同年合同明细中存在唯一已映射项目的同客户合同 → 落入该项目行。</li>
 *   <li>其余（商机集合/其他/无商机绑定/客户匹配多项目或无匹配合同）保留在业务线池（未分配），
 *       原因按代码汇总在 salesUnallocatedDetail（见 {@link RevenueDeliverySummaryVO.UnallocatedItem}）。</li>
 *   <li>项目真实利润 trueProfit = 营收 − 项目工时成本(±预估) − 已分配销售成本 − 其他成本；
 *       业务线/整表 trueProfit = Σ项目真实利润 − 未分配销售成本（=毛利，销售成本已全额计入）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RevenueDeliverySummaryService {

    private static final Map<String, String> PROJECT_ALIASES = Map.of(
            "佳贝艾特", "澳优",
            "海普诺凯", "澳优");
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 未分配销售成本原因代码与中文说明 */
    private static final Map<String, String> UNALLOC_REASONS = Map.of(
            "NO_OPP_LINK", "具体销售项目未关联商机，无客户/成单证据",
            "NO_MATCH_CONTRACT", "已关联商机但年内无同客户成单合同",
            "MULTI_PROJECT", "同客户成单合同对应多个项目，无法唯一归属",
            "POOL_NO_EVIDENCE", "商机集合/其他销售行无成单项目证据，保留业务线级");

    private final RevenueWorklogEntryMapper worklogEntryMapper;
    private final RevenueCostEntryMapper costEntryMapper;
    private final RevenueContractEntryMapper contractEntryMapper;
    private final RevenueDeliveryPlanMapper planMapper;
    private final RevenueOtherCostMapper otherCostMapper;
    private final RevenueMonthService monthService;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;
    private final RevenueSalesProjectMapper salesProjectMapper;
    private final SalesOpportunityMapper opportunityMapper;

    public RevenueDeliverySummaryVO summary(int year, boolean includeEstimate) {
        return summary(year, includeEstimate, LocalDate.now());
    }

    public RevenueDeliverySummaryVO summary(int year, boolean includeEstimate, LocalDate today) {
        List<BusinessLine> lines = businessLineMapper.selectList(new LambdaQueryWrapper<BusinessLine>()
                .eq(BusinessLine::getStatus, 1).orderByAsc(BusinessLine::getId));
        RevenueDeliverySummaryVO vo = base(year, includeEstimate);
        if (lines.isEmpty()) {
            return vo;
        }
        Map<Long, String> lineMode = lines.stream().collect(Collectors.toMap(BusinessLine::getId,
                line -> StringUtils.hasText(line.getRevenueMode()) ? line.getRevenueMode() : "full", (a, b) -> a));
        Map<Long, Boolean> costVisible = lines.stream().collect(Collectors.toMap(BusinessLine::getId,
                line -> line.getCostVisible() == null || line.getCostVisible() == 1, (a, b) -> a));
        Map<Long, Project> projectsById = projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity(), (a, b) -> a));
        Map<Long, Long> aliasToRoot = aliasMap(projectsById);
        Set<String> closed = monthService.closedMonths();
        String yearPrefix = year + "-";
        String currentYearMonth = YM.format(today);

        // 行注册：full=主项目行（别名源不注册）/ aggregate=项目集聚合行 / simple=单行
        Map<String, RowDef> rowsByKey = new LinkedHashMap<>();
        Map<Long, List<RowDef>> rowsOfLine = new LinkedHashMap<>();
        for (BusinessLine line : lines) {
            List<RowDef> defs = new ArrayList<>();
            String mode = lineMode.get(line.getId());
            if ("simple".equals(mode)) {
                defs.add(register(rowsByKey, new RowDef("simple:" + line.getId(), line.getId(), null,
                        line.getName(), true)));
            } else if ("aggregate".equals(mode)) {
                defs.add(register(rowsByKey, new RowDef("agg:" + line.getId(), line.getId(), null,
                        "项目集", true)));
            } else {
                projectsById.values().stream()
                        .filter(p -> Objects.equals(p.getBusinessLineId(), line.getId()) && p.getParentId() == null)
                        .filter(p -> !aliasToRoot.containsKey(p.getId()))
                        .sorted(Comparator.comparing(Project::getId))
                        .forEach(p -> defs.add(register(rowsByKey,
                                new RowDef("p:" + p.getId(), line.getId(), p.getId(), p.getName(), false))));
            }
            rowsOfLine.put(line.getId(), defs);
        }

        // 合同（同年收款销售口径）：OA 总额、已交付按交付月、销售分配候选（客户→项目桶）
        List<RevenueContractEntry> contractRows = contractEntryMapper.selectList(
                new LambdaQueryWrapper<RevenueContractEntry>()
                        .and(w -> w.likeRight(RevenueContractEntry::getSaleMonth, yearPrefix)
                                .or().isNull(RevenueContractEntry::getSaleMonth)));
        Map<String, BigDecimal> oaContract = new HashMap<>();
        Map<String, BigDecimal[]> deliveredByMonth = new HashMap<>();
        Map<String, Set<String>> contractBucketsByCustomer = new HashMap<>();
        for (RevenueContractEntry entry : contractRows) {
            if (entry.getReceivableAmount() == null) {
                continue;
            }
            Long lineId = entry.getBizLineId();
            String key = deliveryBucketKey(lineId, entry.getProjectId(), lineMode.get(lineId),
                    projectsById, aliasToRoot);
            if (key == null) {
                continue;
            }
            oaContract.merge(key, entry.getReceivableAmount(), BigDecimal::add);
            if (entry.getDeliveryDate() != null && entry.getDeliveryDate().getYear() == year
                    && !entry.getDeliveryDate().isAfter(today)) {
                int m = entry.getDeliveryDate().getMonthValue() - 1;
                addMonth(deliveredByMonth, key, m, entry.getReceivableAmount());
            }
            if (lineId != null && entry.getCustomer() != null && rowsByKey.containsKey(key)) {
                String mapKey = lineId + "#" + entry.getCustomer().trim().toLowerCase(Locale.ROOT);
                contractBucketsByCustomer.computeIfAbsent(mapKey, k -> new LinkedHashSet<>()).add(key);
            }
        }

        // 销售项目注册表与商机（成单证据链：具体销售项目 → 商机 → 同客户成单合同）
        Map<Long, RevenueSalesProject> salesProjectsById = salesProjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(RevenueSalesProject::getId, Function.identity(), (a, b) -> a));
        Map<Long, SalesOpportunity> opportunitiesById = opportunityMapper.selectList(null).stream()
                .collect(Collectors.toMap(SalesOpportunity::getId, Function.identity(), (a, b) -> a));

        // 完结月实际工时/成本按桶×月累计（工时=工时明细人月，缺失回退成本明细人月）
        Map<String, MonthAcc> laborAcc = new HashMap<>();
        Map<String, MonthAcc> salesAcc = new HashMap<>();
        for (RevenueWorklogEntry entry : worklogEntryMapper.selectList(new LambdaQueryWrapper<RevenueWorklogEntry>()
                .eq(RevenueWorklogEntry::getPending, 0).likeRight(RevenueWorklogEntry::getYearMonth, yearPrefix))) {
            if (!closed.contains(entry.getYearMonth()) || entry.getBusinessLineId() == null) {
                continue;
            }
            int m = monthIndex(entry.getYearMonth());
            if (m < 0) {
                continue;
            }
            String mode = lineMode.get(entry.getBusinessLineId());
            if (isSales(entry.getWorkType())) {
                bucketOf(salesAcc, laborAcc, entry.getBusinessLineId(), mode, true, m)
                        .addWorklogHours(m, entry.getHours());
            } else {
                String key = laborBucketKey(entry.getBusinessLineId(), entry.getProjectId(), mode,
                        projectsById, aliasToRoot);
                if (key != null) {
                    laborAcc.computeIfAbsent(key, k -> new MonthAcc()).addWorklogHours(m, entry.getHours());
                }
            }
        }
        // 销售分配累计器：allocHours/allocCost 按月落在项目桶；unallocReasonsByLine 记录未分配原因（全年）
        Map<String, BigDecimal[]> allocHours = new HashMap<>();
        Map<String, BigDecimal[]> allocCost = new HashMap<>();
        Map<Long, Map<String, BigDecimal>> unallocReasonsByLine = new HashMap<>();
        for (RevenueCostEntry entry : costEntryMapper.selectList(new LambdaQueryWrapper<RevenueCostEntry>()
                .eq(RevenueCostEntry::getPending, 0).likeRight(RevenueCostEntry::getYearMonth, yearPrefix))) {
            if (!closed.contains(entry.getYearMonth()) || entry.getBusinessLineId() == null) {
                continue;
            }
            int m = monthIndex(entry.getYearMonth());
            if (m < 0) {
                continue;
            }
            String mode = lineMode.get(entry.getBusinessLineId());
            boolean visible = costVisible.getOrDefault(entry.getBusinessLineId(), true);
            MonthAcc acc;
            if (isSales(entry.getWorkType())) {
                acc = bucketOf(salesAcc, laborAcc, entry.getBusinessLineId(), mode, true, m);
                if (visible && !"simple".equals(mode)) {
                    allocateSales(entry, m, mode, rowsByKey, salesProjectsById, opportunitiesById,
                            contractBucketsByCustomer, aliasToRoot, allocHours, allocCost, unallocReasonsByLine);
                }
            } else {
                String key = laborBucketKey(entry.getBusinessLineId(), entry.getProjectId(), mode,
                        projectsById, aliasToRoot);
                if (key == null) {
                    continue;
                }
                acc = laborAcc.computeIfAbsent(key, k -> new MonthAcc());
            }
            acc.addCostHours(m, entry.getHours());
            if (visible) {
                acc.addCost(m, entry.getCostAmount());
            }
        }

        // 预估交付计划：金额 + 预估工时成本
        Map<String, BigDecimal[]> planAmount = new HashMap<>();
        Map<String, BigDecimal[]> planLabor = new HashMap<>();
        for (RevenueDeliveryPlan plan : planMapper.selectList(new LambdaQueryWrapper<RevenueDeliveryPlan>()
                .likeRight(RevenueDeliveryPlan::getYearMonth, yearPrefix))) {
            String key = deliveryBucketKey(plan.getBusinessLineId(), plan.getProjectId(),
                    lineMode.get(plan.getBusinessLineId()), projectsById, aliasToRoot);
            if (key == null) {
                continue;
            }
            int m = monthIndex(plan.getYearMonth());
            if (m < 0) {
                continue;
            }
            addMonth(planAmount, key, m, plan.getAmountYuan());
            addMonth(planLabor, key, m, plan.getLaborCostYuan());
        }

        // 其他成本：类型×桶×月（仅归属月 <= 当月）
        Map<String, Map<String, BigDecimal[]>> otherByType = new HashMap<>();
        for (RevenueOtherCost cost : otherCostMapper.selectList(new LambdaQueryWrapper<RevenueOtherCost>()
                .likeRight(RevenueOtherCost::getYearMonth, yearPrefix))) {
            String key = deliveryBucketKey(cost.getBusinessLineId(), cost.getProjectId(),
                    lineMode.get(cost.getBusinessLineId()), projectsById, aliasToRoot);
            if (key == null || cost.getCostType() == null || cost.getAmountYuan() == null
                    || cost.getYearMonth().compareTo(currentYearMonth) > 0) {
                continue;
            }
            int m = monthIndex(cost.getYearMonth());
            if (m < 0) {
                continue;
            }
            addMonth(otherByType.computeIfAbsent(cost.getCostType(), k -> new HashMap<>()), key, m,
                    cost.getAmountYuan());
        }

        // 组装输出：业务线 → 项目行 + 线 totals（扣销售成本）
        for (BusinessLine line : lines) {
            RevenueDeliverySummaryVO.Line out = new RevenueDeliverySummaryVO.Line();
            out.setBusinessLineId(line.getId());
            out.setBusinessLineName(line.getName());
            MonthAcc sales = salesAcc.get("s:" + line.getId());
            BigDecimal lineSalesHours = BigDecimal.ZERO;
            BigDecimal lineSalesCost = BigDecimal.ZERO;
            if (sales != null) {
                for (int m = 0; m < 12; m++) {
                    lineSalesHours = lineSalesHours.add(sales.hoursOf(m));
                    lineSalesCost = lineSalesCost.add(sales.costOf(m));
                }
            }
            out.setSalesHours(lineSalesHours);
            out.setSalesCost(lineSalesCost);

            RevenueDeliverySummaryVO.ProjectRow totals = newTotalsRow();
            List<RowDef> defs = rowsOfLine.get(line.getId());
            for (RowDef def : defs) {
                MonthAcc acc = laborAcc.get(def.rowKey);
                RevenueDeliverySummaryVO.ProjectRow row = new RevenueDeliverySummaryVO.ProjectRow();
                row.setProjectId(def.projectId);
                row.setName(def.name);
                row.setIsAggregate(def.aggregate);
                row.setOaContract(oaContract.getOrDefault(def.rowKey, BigDecimal.ZERO));
                row.setH1(window(def.rowKey, acc, deliveredByMonth, planAmount, planLabor, otherByType,
                        allocHours, allocCost, 0, 5, includeEstimate));
                row.setH2(window(def.rowKey, acc, deliveredByMonth, planAmount, planLabor, otherByType,
                        allocHours, allocCost, 6, 11, includeEstimate));
                row.setYtd(window(def.rowKey, acc, deliveredByMonth, planAmount, planLabor, otherByType,
                        allocHours, allocCost, 0, 11, includeEstimate));
                out.getProjects().add(row);
                addInto(totals, row);
            }
            addSalesInto(totals, sales, includeEstimate);
            out.setTotals(totals);

            // 线级全年销售拆分（= ytd 时窗）与未分配原因明细
            out.setSalesAllocatedHours(totals.getYtd().getAllocatedSalesHours());
            out.setSalesAllocatedCost(totals.getYtd().getAllocatedSalesCost());
            out.setSalesUnallocatedHours(totals.getYtd().getUnallocatedSalesHours());
            out.setSalesUnallocatedCost(totals.getYtd().getUnallocatedSalesCost());
            Map<String, BigDecimal> reasons = unallocReasonsByLine.get(line.getId());
            if (reasons != null) {
                reasons.forEach((code, cost) -> out.getSalesUnallocatedDetail()
                        .add(item(code, cost)));
            }
            out.getSalesUnallocatedDetail().sort(Comparator
                    .comparing(RevenueDeliverySummaryVO.UnallocatedItem::getCost).reversed());
            vo.getLines().add(out);
        }

        // 整表汇总（YTD 口径，随含预估开关）
        BigDecimal totalOa = BigDecimal.ZERO;
        BigDecimal[] totalsArr = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        Map<String, BigDecimal> globalReasons = new HashMap<>();
        for (RevenueDeliverySummaryVO.Line out : vo.getLines()) {
            RevenueDeliverySummaryVO.ProjectRow totals = out.getTotals();
            totalOa = totalOa.add(totals.getOaContract());
            RevenueDeliverySummaryVO.Window ytd = totals.getYtd();
            totalsArr[0] = totalsArr[0].add(ytd.getDelivered());
            totalsArr[1] = totalsArr[1].add(ytd.getEstimated());
            totalsArr[2] = totalsArr[2].add(ytd.getProjectLaborCost());
            if (includeEstimate) {
                totalsArr[2] = totalsArr[2].add(nz(ytd.getEstimatedLaborCost()));
            }
            totalsArr[2] = totalsArr[2].add(nz(ytd.getSalesCost()));
            totalsArr[3] = totalsArr[3].add(nz(ytd.getAllocatedSalesCost()));
            totalsArr[4] = totalsArr[4].add(nz(ytd.getUnallocatedSalesCost()));
            totalsArr[5] = totalsArr[5].add(otherOf(ytd));
            totalsArr[6] = totalsArr[6].add(nz(ytd.getTrueProfit()));
            out.getSalesUnallocatedDetail().forEach(item ->
                    globalReasons.merge(item.getReason(), item.getCost(), BigDecimal::add));
        }
        RevenueDeliverySummaryVO.Overview overview = vo.getOverview();
        overview.setTotalOaContract(totalOa);
        overview.setTotalDelivered(totalsArr[0]);
        overview.setTotalEstimated(totalsArr[1]);
        overview.setTotalLaborCost(totalsArr[2]);
        overview.setTotalAllocatedSalesCost(totalsArr[3]);
        overview.setTotalUnallocatedSalesCost(totalsArr[4]);
        overview.setTotalOtherCost(totalsArr[5]);
        overview.setTotalProfit(totalsArr[6]);
        overview.setProfitRate(rate(totalsArr[6],
                totalsArr[0].add(includeEstimate ? totalsArr[1] : BigDecimal.ZERO)));
        overview.setTotalTrueProfit(totalsArr[6]);
        overview.setTrueProfitRate(overview.getProfitRate());
        globalReasons.forEach((code, cost) -> overview.getSalesUnallocatedDetail().add(item(code, cost)));
        overview.getSalesUnallocatedDetail().sort(Comparator
                .comparing(RevenueDeliverySummaryVO.UnallocatedItem::getCost).reversed());
        return vo;
    }

    // ------------------------------------------------------------ 销售成本分配

    /**
     * 成单销售成本分配（确定性规则，无证据不分配）：
     * specific 销售行 → 销售项目商机 → 商机客户同年合同存在唯一项目 → 落该项目桶；
     * 其余（pool/other/无商机/多项目/无合同）→ 记入未分配原因。
     */
    private void allocateSales(RevenueCostEntry entry, int month, String mode,
                               Map<String, RowDef> rowsByKey,
                               Map<Long, RevenueSalesProject> salesProjectsById,
                               Map<Long, SalesOpportunity> opportunitiesById,
                               Map<String, Set<String>> contractBucketsByCustomer,
                               Map<Long, Long> aliasToRoot,
                               Map<String, BigDecimal[]> allocHours,
                               Map<String, BigDecimal[]> allocCost,
                               Map<Long, Map<String, BigDecimal>> unallocReasonsByLine) {
        String bucket = null;
        String reason = null;
        if ("specific".equals(entry.getSalesKind()) && entry.getSalesProjectId() != null) {
            RevenueSalesProject salesProject = salesProjectsById.get(entry.getSalesProjectId());
            SalesOpportunity opportunity = salesProject == null || salesProject.getOpportunityId() == null
                    ? null : opportunitiesById.get(salesProject.getOpportunityId());
            String customer = opportunity == null ? null
                    : (opportunity.getCustomer() == null ? null
                    : opportunity.getCustomer().trim().toLowerCase(Locale.ROOT));
            if (salesProject == null || opportunity == null || !StringUtils.hasText(customer)) {
                reason = "NO_OPP_LINK";
            } else {
                Set<String> candidates = contractBucketsByCustomer.get(
                        entry.getBusinessLineId() + "#" + customer);
                if (candidates == null || candidates.isEmpty()) {
                    reason = "NO_MATCH_CONTRACT";
                } else if (candidates.size() == 1) {
                    bucket = candidates.iterator().next();
                } else {
                    reason = "MULTI_PROJECT";
                }
            }
        } else {
            reason = "POOL_NO_EVIDENCE";
        }
        if (bucket != null && rowsByKey.containsKey(bucket)) {
            addMonth(allocHours, bucket, month, entry.getHours());
            addMonth(allocCost, bucket, month, entry.getCostAmount());
            return;
        }
        if (reason != null && entry.getCostAmount() != null
                && entry.getCostAmount().compareTo(BigDecimal.ZERO) > 0) {
            Map<String, BigDecimal> reasons = unallocReasonsByLine
                    .computeIfAbsent(entry.getBusinessLineId(), k -> new HashMap<>());
            reasons.merge(reason, entry.getCostAmount(), BigDecimal::add);
        }
    }

    // ------------------------------------------------------------ 窗口计算

    private RevenueDeliverySummaryVO.Window window(String rowKey, MonthAcc acc,
                                                    Map<String, BigDecimal[]> delivered,
                                                    Map<String, BigDecimal[]> planAmount,
                                                    Map<String, BigDecimal[]> planLabor,
                                                    Map<String, Map<String, BigDecimal[]>> otherByType,
                                                    Map<String, BigDecimal[]> allocHours,
                                                    Map<String, BigDecimal[]> allocCost,
                                                    int from, int to, boolean includeEstimate) {
        RevenueDeliverySummaryVO.Window win = newRevenueWindow();
        BigDecimal deliveredSum = BigDecimal.ZERO;
        BigDecimal estimated = BigDecimal.ZERO;
        BigDecimal hours = BigDecimal.ZERO;
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal estLabor = BigDecimal.ZERO;
        BigDecimal allocH = BigDecimal.ZERO;
        BigDecimal allocC = BigDecimal.ZERO;
        for (int m = from; m <= to; m++) {
            deliveredSum = deliveredSum.add(monthOf(delivered, rowKey, m));
            estimated = estimated.add(monthOf(planAmount, rowKey, m));
            estLabor = estLabor.add(monthOf(planLabor, rowKey, m));
            if (acc != null) {
                hours = hours.add(acc.hoursOf(m));
                labor = labor.add(acc.costOf(m));
            }
            allocH = allocH.add(monthOf(allocHours, rowKey, m));
            allocC = allocC.add(monthOf(allocCost, rowKey, m));
        }
        win.setDelivered(deliveredSum);
        win.setEstimated(estimated);
        win.setProjectHours(hours);
        win.setProjectLaborCost(labor);
        win.setEstimatedLaborCost(estLabor);
        win.setSalesHours(BigDecimal.ZERO);
        win.setSalesCost(BigDecimal.ZERO);
        win.setAllocatedSalesHours(allocH);
        win.setAllocatedSalesCost(allocC);
        win.setUnallocatedSalesHours(BigDecimal.ZERO);
        win.setUnallocatedSalesCost(BigDecimal.ZERO);
        win.setOtherCosts(otherCostsOf(otherByType, rowKey, from, to));
        BigDecimal revenue = deliveredSum.add(includeEstimate ? estimated : BigDecimal.ZERO);
        BigDecimal cost = labor.add(includeEstimate ? estLabor : BigDecimal.ZERO);
        BigDecimal laborProfit = revenue.subtract(cost);
        BigDecimal gross = laborProfit.subtract(win.getOtherCosts().getTotal());
        win.setLaborProfit(laborProfit);
        win.setGrossProfit(gross);
        win.setGrossRate(rate(gross, revenue));
        // 项目真实利润：毛利 − 已分配销售成本（未分配销售成本不进项目行）
        BigDecimal trueProfit = gross.subtract(allocC);
        win.setTrueProfit(trueProfit);
        win.setTrueProfitRate(rate(trueProfit, revenue));
        return win;
    }

    private void addInto(RevenueDeliverySummaryVO.ProjectRow totals, RevenueDeliverySummaryVO.ProjectRow row) {
        totals.setOaContract(add(totals.getOaContract(), row.getOaContract()));
        addWindow(totals.getH1(), row.getH1());
        addWindow(totals.getH2(), row.getH2());
        addWindow(totals.getYtd(), row.getYtd());
    }

    private void addWindow(RevenueDeliverySummaryVO.Window target, RevenueDeliverySummaryVO.Window src) {
        target.setDelivered(add(target.getDelivered(), src.getDelivered()));
        target.setEstimated(add(target.getEstimated(), src.getEstimated()));
        target.setProjectHours(add(target.getProjectHours(), src.getProjectHours()));
        target.setProjectLaborCost(add(target.getProjectLaborCost(), src.getProjectLaborCost()));
        target.setEstimatedLaborCost(add(target.getEstimatedLaborCost(), src.getEstimatedLaborCost()));
        target.setAllocatedSalesHours(add(target.getAllocatedSalesHours(), src.getAllocatedSalesHours()));
        target.setAllocatedSalesCost(add(target.getAllocatedSalesCost(), src.getAllocatedSalesCost()));
        RevenueDeliverySummaryVO.OtherCosts other = target.getOtherCosts();
        other.setPartner(add(other.getPartner(), src.getOtherCosts().getPartner()));
        other.setServer(add(other.getServer(), src.getOtherCosts().getServer()));
        other.setOther(add(other.getOther(), src.getOtherCosts().getOther()));
        other.setTotal(other.getPartner().add(other.getServer()).add(other.getOther()));
    }

    /** 线 totals：项目行加总后补该线销售（窗口）工时/成本，拆分已分配/未分配并重算毛利 */
    private void addSalesInto(RevenueDeliverySummaryVO.ProjectRow totals, MonthAcc sales, boolean includeEstimate) {
        for (RevenueDeliverySummaryVO.Window win : List.of(totals.getH1(), totals.getH2(), totals.getYtd())) {
            int from = win == totals.getH1() ? 0 : (win == totals.getH2() ? 6 : 0);
            int to = win == totals.getH1() ? 5 : 11;
            BigDecimal sHours = BigDecimal.ZERO;
            BigDecimal sCost = BigDecimal.ZERO;
            if (sales != null) {
                for (int m = from; m <= to; m++) {
                    sHours = sHours.add(sales.hoursOf(m));
                    sCost = sCost.add(sales.costOf(m));
                }
            }
            win.setSalesHours(sHours);
            win.setSalesCost(sCost);
            // 未分配 = 该线销售全口径 − 已落入项目行的部分（成本同源成本行，恒非负；工时按显示口径取整保护）
            BigDecimal allocatedC = win.getAllocatedSalesCost();
            BigDecimal allocatedH = win.getAllocatedSalesHours();
            win.setUnallocatedSalesCost(sCost.subtract(allocatedC).max(BigDecimal.ZERO));
            win.setUnallocatedSalesHours(sHours.subtract(allocatedH).max(BigDecimal.ZERO));
            recompute(win, includeEstimate);
        }
    }

    private void recompute(RevenueDeliverySummaryVO.Window win, boolean includeEstimate) {
        BigDecimal revenue = win.getDelivered()
                .add(includeEstimate ? nz(win.getEstimated()) : BigDecimal.ZERO);
        BigDecimal labor = win.getProjectLaborCost()
                .add(includeEstimate ? nz(win.getEstimatedLaborCost()) : BigDecimal.ZERO)
                .add(nz(win.getSalesCost()));
        BigDecimal laborProfit = revenue.subtract(labor);
        BigDecimal gross = laborProfit.subtract(otherOf(win));
        win.setLaborProfit(laborProfit);
        win.setGrossProfit(gross);
        win.setGrossRate(rate(gross, revenue));
        // 线/表汇总真实利润 = Σ项目真实利润 − 未分配销售成本；销售成本已全额计入，故与毛利一致
        win.setTrueProfit(gross);
        win.setTrueProfitRate(win.getGrossRate());
    }

    // ------------------------------------------------------------ 工具

    private RevenueDeliverySummaryVO.UnallocatedItem item(String reason, BigDecimal cost) {
        RevenueDeliverySummaryVO.UnallocatedItem item = new RevenueDeliverySummaryVO.UnallocatedItem();
        item.setReason(reason);
        item.setLabel(UNALLOC_REASONS.getOrDefault(reason, reason));
        item.setCost(cost);
        return item;
    }

    private RowDef register(Map<String, RowDef> rowsByKey, RowDef def) {
        rowsByKey.put(def.rowKey, def);
        return def;
    }

    /** sales 行：simple 线并入单行桶，其余进业务线销售桶 */
    private MonthAcc bucketOf(Map<String, MonthAcc> salesAcc, Map<String, MonthAcc> laborAcc,
                              Long lineId, String mode, boolean salesEntry, int month) {
        if (!salesEntry) {
            throw new IllegalArgumentException("bucketOf 仅用于 sales 行");
        }
        if ("simple".equals(mode)) {
            return laborAcc.computeIfAbsent("simple:" + lineId, k -> new MonthAcc());
        }
        return salesAcc.computeIfAbsent("s:" + lineId, k -> new MonthAcc());
    }

    /** 实际工时/成本行项目桶：simple=单行；aggregate=项目集；full=p-根项目（别名归并），业务线级项目行不入桶 */
    private String laborBucketKey(Long lineId, Long projectId, String mode,
                                  Map<Long, Project> projectsById, Map<Long, Long> aliasToRoot) {
        if ("simple".equals(mode)) {
            return "simple:" + lineId;
        }
        if ("aggregate".equals(mode)) {
            return "agg:" + lineId;
        }
        if (projectId == null) {
            return null;
        }
        Long root = rootIdOf(projectId, projectsById);
        return root == null ? null : "p:" + aliasToRoot.getOrDefault(root, root);
    }

    /** 合同/计划/其他成本归属桶：项目行；业务线级走 aggregate/simple 聚合行；full 无聚合行返回 null */
    private String deliveryBucketKey(Long lineId, Long projectId, String mode,
                                     Map<Long, Project> projectsById, Map<Long, Long> aliasToRoot) {
        if (projectId == null) {
            if ("aggregate".equals(mode)) {
                return "agg:" + lineId;
            }
            if ("simple".equals(mode)) {
                return "simple:" + lineId;
            }
            return null;
        }
        if ("aggregate".equals(mode)) {
            return "agg:" + lineId;
        }
        if ("simple".equals(mode)) {
            return "simple:" + lineId;
        }
        Long root = rootIdOf(projectId, projectsById);
        return root == null ? null : "p:" + aliasToRoot.getOrDefault(root, root);
    }

    private boolean isSales(String workType) {
        return "sales".equals(workType);
    }

    private Long rootIdOf(Long projectId, Map<Long, Project> projectsById) {
        Project project = projectsById.get(projectId);
        while (project != null && project.getParentId() != null) {
            project = projectsById.get(project.getParentId());
        }
        return project == null ? null : project.getId();
    }

    private Map<Long, Long> aliasMap(Map<Long, Project> projectsById) {
        Map<Long, Long> aliasToRoot = new HashMap<>();
        PROJECT_ALIASES.forEach((sourceName, targetName) -> {
            List<Project> sources = projectsById.values().stream()
                    .filter(p -> sourceName.equals(p.getName())).toList();
            for (Project source : sources) {
                projectsById.values().stream()
                        .filter(p -> targetName.equals(p.getName())
                                && Objects.equals(p.getBusinessLineId(), source.getBusinessLineId()))
                        .findFirst()
                        .ifPresent(target -> aliasToRoot.put(source.getId(), target.getId()));
            }
        });
        return aliasToRoot;
    }

    private RevenueDeliverySummaryVO.OtherCosts otherCostsOf(Map<String, Map<String, BigDecimal[]>> byType,
                                                             String rowKey, int from, int to) {
        RevenueDeliverySummaryVO.OtherCosts other = new RevenueDeliverySummaryVO.OtherCosts();
        BigDecimal partner = BigDecimal.ZERO;
        BigDecimal server = BigDecimal.ZERO;
        BigDecimal otherType = BigDecimal.ZERO;
        for (Map.Entry<String, Map<String, BigDecimal[]>> typeEntry : byType.entrySet()) {
            BigDecimal[] months = typeEntry.getValue().get(rowKey);
            if (months == null) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int m = from; m <= to; m++) {
                if (months[m] != null) {
                    sum = sum.add(months[m]);
                }
            }
            if ("partner".equals(typeEntry.getKey())) {
                partner = partner.add(sum);
            } else if ("server".equals(typeEntry.getKey())) {
                server = server.add(sum);
            } else {
                otherType = otherType.add(sum);
            }
        }
        other.setPartner(partner);
        other.setServer(server);
        other.setOther(otherType);
        other.setTotal(partner.add(server).add(otherType));
        return other;
    }

    private BigDecimal otherOf(RevenueDeliverySummaryVO.Window win) {
        return win.getOtherCosts() == null ? BigDecimal.ZERO : win.getOtherCosts().getTotal();
    }

    private BigDecimal rate(BigDecimal profit, BigDecimal revenue) {
        if (revenue == null || revenue.signum() <= 0) {
            return null;
        }
        return profit.multiply(new BigDecimal("100")).divide(revenue, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal add(BigDecimal a, BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a).add(b == null ? BigDecimal.ZERO : b);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private void addMonth(Map<String, BigDecimal[]> map, String key, int month, BigDecimal value) {
        if (value == null) {
            return;
        }
        BigDecimal[] months = map.computeIfAbsent(key, k -> new BigDecimal[12]);
        months[month] = (months[month] == null ? BigDecimal.ZERO : months[month]).add(value);
    }

    private BigDecimal monthOf(Map<String, BigDecimal[]> map, String key, int month) {
        BigDecimal[] months = map.get(key);
        return months == null || months[month] == null ? BigDecimal.ZERO : months[month];
    }

    private int monthIndex(String yearMonth) {
        if (yearMonth == null || yearMonth.length() < 7) {
            return -1;
        }
        try {
            int month = Integer.parseInt(yearMonth.substring(5, 7));
            return month >= 1 && month <= 12 ? month - 1 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private RevenueDeliverySummaryVO.ProjectRow newTotalsRow() {
        RevenueDeliverySummaryVO.ProjectRow totals = new RevenueDeliverySummaryVO.ProjectRow();
        totals.setName("合计");
        totals.setProjectId(null);
        totals.setIsAggregate(false);
        totals.setOaContract(BigDecimal.ZERO);
        totals.setH1(newRevenueWindow());
        totals.setH2(newRevenueWindow());
        totals.setYtd(newRevenueWindow());
        return totals;
    }

    private RevenueDeliverySummaryVO.Window newRevenueWindow() {
        RevenueDeliverySummaryVO.Window win = new RevenueDeliverySummaryVO.Window();
        win.setDelivered(BigDecimal.ZERO);
        win.setEstimated(BigDecimal.ZERO);
        win.setProjectHours(BigDecimal.ZERO);
        win.setProjectLaborCost(BigDecimal.ZERO);
        win.setEstimatedLaborCost(BigDecimal.ZERO);
        win.setSalesHours(BigDecimal.ZERO);
        win.setSalesCost(BigDecimal.ZERO);
        win.setAllocatedSalesHours(BigDecimal.ZERO);
        win.setAllocatedSalesCost(BigDecimal.ZERO);
        win.setUnallocatedSalesHours(BigDecimal.ZERO);
        win.setUnallocatedSalesCost(BigDecimal.ZERO);
        RevenueDeliverySummaryVO.OtherCosts other = new RevenueDeliverySummaryVO.OtherCosts();
        other.setPartner(BigDecimal.ZERO);
        other.setServer(BigDecimal.ZERO);
        other.setOther(BigDecimal.ZERO);
        other.setTotal(BigDecimal.ZERO);
        win.setOtherCosts(other);
        win.setLaborProfit(BigDecimal.ZERO);
        win.setGrossProfit(BigDecimal.ZERO);
        win.setTrueProfit(BigDecimal.ZERO);
        return win;
    }

    private RevenueDeliverySummaryVO base(int year, boolean includeEstimate) {
        RevenueDeliverySummaryVO vo = new RevenueDeliverySummaryVO();
        vo.setYear(year);
        vo.setIncludeEstimate(includeEstimate);
        RevenueDeliverySummaryVO.Overview overview = new RevenueDeliverySummaryVO.Overview();
        overview.setIncludeEstimate(includeEstimate);
        overview.setTotalOaContract(BigDecimal.ZERO);
        overview.setTotalDelivered(BigDecimal.ZERO);
        overview.setTotalEstimated(BigDecimal.ZERO);
        overview.setTotalLaborCost(BigDecimal.ZERO);
        overview.setTotalAllocatedSalesCost(BigDecimal.ZERO);
        overview.setTotalUnallocatedSalesCost(BigDecimal.ZERO);
        overview.setTotalOtherCost(BigDecimal.ZERO);
        overview.setTotalProfit(BigDecimal.ZERO);
        overview.setTotalTrueProfit(BigDecimal.ZERO);
        vo.setOverview(overview);
        return vo;
    }

    private static final class RowDef {
        private final String rowKey;
        private final Long businessLineId;
        private final Long projectId;
        private final String name;
        private final boolean aggregate;

        private RowDef(String rowKey, Long businessLineId, Long projectId, String name, boolean aggregate) {
            this.rowKey = rowKey;
            this.businessLineId = businessLineId;
            this.projectId = projectId;
            this.name = name;
            this.aggregate = aggregate;
        }
    }

    /** 月度累计器：工时明细人月 / 成本明细人月与金额（金额仅在成本可见业务线累计） */
    private static final class MonthAcc {
        private final BigDecimal[] worklogHours = new BigDecimal[12];
        private final BigDecimal[] costHours = new BigDecimal[12];
        private final BigDecimal[] cost = new BigDecimal[12];

        private void addWorklogHours(int m, BigDecimal v) {
            worklogHours[m] = (worklogHours[m] == null ? BigDecimal.ZERO : worklogHours[m]).add(v);
        }

        private void addCostHours(int m, BigDecimal v) {
            costHours[m] = (costHours[m] == null ? BigDecimal.ZERO : costHours[m]).add(v);
        }

        private void addCost(int m, BigDecimal v) {
            cost[m] = (cost[m] == null ? BigDecimal.ZERO : cost[m]).add(v);
        }

        private BigDecimal hoursOf(int m) {
            if (worklogHours[m] != null) {
                return worklogHours[m];
            }
            return costHours[m] == null ? BigDecimal.ZERO : costHours[m];
        }

        private BigDecimal costOf(int m) {
            return cost[m] == null ? BigDecimal.ZERO : cost[m];
        }
    }
}
