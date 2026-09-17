package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.KpiAlertRule;
import com.bu.management.entity.KpiDeviationNote;
import com.bu.management.entity.KpiTarget;
import com.bu.management.entity.KpiWeeklySnapshot;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueMonthClose;
import com.bu.management.entity.RevenueOtherCost;
import com.bu.management.entity.Task;
import com.bu.management.entity.WorkLog;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.KpiAlertRuleMapper;
import com.bu.management.mapper.KpiDeviationNoteMapper;
import com.bu.management.mapper.KpiTargetMapper;
import com.bu.management.mapper.KpiWeeklySnapshotMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueMonthCloseMapper;
import com.bu.management.mapper.RevenueOtherCostMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.WorkLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * KPI 周快照计算与预警扫描。
 * <p>口径（交付口径，不用财报考核口径）：</p>
 * <ul>
 *   <li>YTD 营收 = Σ 合同应收金额（交付日期属于当年且 ≤ 快照日）</li>
 *   <li>YTD 直接成本 = Σ 已交付合同行的 短信/直接/三方采购成本</li>
 *   <li>YTD 人工成本 = 已完结月实际成本 + 当月未完结估算（work_log 实时工时 ÷ 174h/人月 × 综合单价）</li>
 *   <li>综合单价 = 当年已完结月成本合计 ÷ 已完结月工时合计</li>
 *   <li>YTD 毛利 = 营收 − 直接成本 − 人工成本 − 其他成本（归属月 ≤ 当月）</li>
 *   <li>周环比增量 = 本快照 YTD − 上一快照 YTD</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KpiSnapshotService {

    /** 人月折算小时数：8h × 21.75 工作日 */
    private static final BigDecimal HOURS_PER_PERSON_MONTH = new BigDecimal("174");
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    private final BusinessLineMapper businessLineMapper;
    private final RevenueContractEntryMapper contractEntryMapper;
    private final RevenueCostEntryMapper costEntryMapper;
    private final RevenueOtherCostMapper otherCostMapper;
    private final RevenueMonthCloseMapper monthCloseMapper;
    private final WorkLogMapper workLogMapper;
    private final TaskMapper taskMapper;
    private final RequirementMapper requirementMapper;
    private final KpiWeeklySnapshotMapper snapshotMapper;
    private final KpiTargetMapper targetMapper;
    private final KpiAlertRuleMapper alertRuleMapper;
    private final KpiDeviationNoteMapper noteMapper;

    /** 计算并落库指定周截止日的快照（幂等：同周日同分组覆盖），随后扫描预警。 */
    @Transactional
    public List<KpiWeeklySnapshot> runWeeklySnapshot(LocalDate weekEndDate, Long operatorUserId) {
        int year = weekEndDate.getYear();
        YearMonth currentMonth = YearMonth.from(weekEndDate);
        Set<String> closedMonths = monthCloseMapper.selectList(new LambdaQueryWrapper<RevenueMonthClose>()
                        .isNotNull(RevenueMonthClose::getClosedAt))
                .stream().map(RevenueMonthClose::getYearMonth).collect(Collectors.toSet());

        Map<String, List<Long>> groupLines = reportGroupLines();
        List<KpiWeeklySnapshot> results = new java.util.ArrayList<>();
        for (Map.Entry<String, List<Long>> groupEntry : groupLines.entrySet()) {
            String group = groupEntry.getKey();
            List<Long> lineIds = groupEntry.getValue();

            BigDecimal[] revenueAndDirect = deliveredRevenueAndDirectCost(lineIds, year, weekEndDate);
            BigDecimal laborClosed = closedLaborCost(lineIds, year, closedMonths);
            boolean estimated = !closedMonths.contains(currentMonth.toString());
            BigDecimal laborEstimate = BigDecimal.ZERO;
            if (estimated) {
                laborEstimate = estimateCurrentMonthLabor(lineIds, currentMonth, weekEndDate, closedUnitPrice(lineIds, year, closedMonths));
            }
            BigDecimal otherCost = otherCost(lineIds, currentMonth);
            BigDecimal laborTotal = laborClosed.add(laborEstimate);
            BigDecimal profit = revenueAndDirect[0].subtract(revenueAndDirect[1]).subtract(laborTotal).subtract(otherCost);

            KpiWeeklySnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<KpiWeeklySnapshot>()
                    .eq(KpiWeeklySnapshot::getWeekEndDate, weekEndDate)
                    .eq(KpiWeeklySnapshot::getReportGroup, group));
            if (snapshot == null) {
                snapshot = new KpiWeeklySnapshot();
                snapshot.setWeekEndDate(weekEndDate);
                snapshot.setReportGroup(group);
            }
            snapshot.setYtdRevenue(revenueAndDirect[0]);
            snapshot.setYtdDirectCost(revenueAndDirect[1]);
            snapshot.setYtdLaborCost(laborTotal);
            snapshot.setYtdOtherCost(otherCost);
            snapshot.setYtdProfit(profit);
            snapshot.setEstimated(estimated ? 1 : 0);

            KpiWeeklySnapshot previous = snapshotMapper.selectOne(new LambdaQueryWrapper<KpiWeeklySnapshot>()
                    .eq(KpiWeeklySnapshot::getReportGroup, group)
                    .lt(KpiWeeklySnapshot::getWeekEndDate, weekEndDate)
                    .orderByDesc(KpiWeeklySnapshot::getWeekEndDate)
                    .last("LIMIT 1"));
            BigDecimal prevRevenue = previous == null ? BigDecimal.ZERO : previous.getYtdRevenue();
            BigDecimal prevProfit = previous == null ? BigDecimal.ZERO : previous.getYtdProfit();
            snapshot.setWeekDeltaRevenue(snapshot.getYtdRevenue().subtract(prevRevenue));
            snapshot.setWeekDeltaProfit(profit.subtract(prevProfit));

            if (snapshot.getId() == null) {
                snapshotMapper.insert(snapshot);
            } else {
                snapshotMapper.updateById(snapshot);
            }
            scanAlerts(snapshot, year, operatorUserId);
            results.add(snapshot);
        }
        return results;
    }

    /** 参与 KPI 周报的分组 → 业务线ID 列表（business_line.kpi_report_group 非空） */
    public Map<String, List<Long>> reportGroupLines() {
        Map<String, List<Long>> groupLines = new HashMap<>();
        businessLineMapper.selectList(new LambdaQueryWrapper<BusinessLine>()
                        .isNotNull(BusinessLine::getKpiReportGroup))
                .forEach(line -> groupLines.computeIfAbsent(line.getKpiReportGroup(), k -> new java.util.ArrayList<>())
                        .add(line.getId()));
        return groupLines;
    }

    // ------------------------------------------------------------------ 口径计算

    /** [0]=YTD交付营收（应收口径） [1]=YTD直接成本（短信+直接+三方采购） */
    private BigDecimal[] deliveredRevenueAndDirectCost(List<Long> lineIds, int year, LocalDate asOf) {
        List<RevenueContractEntry> entries = contractEntryMapper.selectList(new LambdaQueryWrapper<RevenueContractEntry>()
                .in(RevenueContractEntry::getBizLineId, lineIds)
                .isNotNull(RevenueContractEntry::getDeliveryDate)
                .ge(RevenueContractEntry::getDeliveryDate, LocalDate.of(year, 1, 1))
                .le(RevenueContractEntry::getDeliveryDate, asOf));
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal direct = BigDecimal.ZERO;
        for (RevenueContractEntry entry : entries) {
            revenue = revenue.add(nullToZero(entry.getReceivableAmount()));
            direct = direct.add(nullToZero(entry.getSmsCost()))
                    .add(nullToZero(entry.getDirectCost()))
                    .add(nullToZero(entry.getThirdPartyCost()));
        }
        return new BigDecimal[]{revenue, direct};
    }

    /** 当年已完结月实际人工成本（成本明细口径，含项目+销售） */
    private BigDecimal closedLaborCost(List<Long> lineIds, int year, Set<String> closedMonths) {
        if (closedMonths.isEmpty()) {
            return BigDecimal.ZERO;
        }
        String prefix = year + "-";
        List<String> months = closedMonths.stream().filter(m -> m.startsWith(prefix)).sorted().toList();
        if (months.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<RevenueCostEntry> entries = costEntryMapper.selectList(new LambdaQueryWrapper<RevenueCostEntry>()
                .in(RevenueCostEntry::getBusinessLineId, lineIds)
                .in(RevenueCostEntry::getYearMonth, months));
        return entries.stream().map(e -> nullToZero(e.getCostAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 综合单价 = 当年已完结月成本合计 ÷ 已完结月工时合计（人月）；无完结月为 null */
    private BigDecimal closedUnitPrice(List<Long> lineIds, int year, Set<String> closedMonths) {
        String prefix = year + "-";
        List<String> months = closedMonths.stream().filter(m -> m.startsWith(prefix)).sorted().toList();
        if (months.isEmpty()) {
            return null;
        }
        List<RevenueCostEntry> entries = costEntryMapper.selectList(new LambdaQueryWrapper<RevenueCostEntry>()
                .in(RevenueCostEntry::getBusinessLineId, lineIds)
                .in(RevenueCostEntry::getYearMonth, months));
        BigDecimal cost = entries.stream().map(e -> nullToZero(e.getCostAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal hours = entries.stream().map(e -> nullToZero(e.getHours())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (hours.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return cost.divide(hours, 2, RoundingMode.HALF_UP);
    }

    /** 当月未完结人工估算：work_log(当月 ≤ 快照日) 经 task → requirement.business_line_id 归集，小时÷174 × 综合单价 */
    private BigDecimal estimateCurrentMonthLabor(List<Long> lineIds, YearMonth month, LocalDate asOf, BigDecimal unitPrice) {
        if (unitPrice == null) {
            return BigDecimal.ZERO;
        }
        List<Map<String, Object>> rows = workLogMapper.findEstimateRows(month.atDay(1), asOf);
        if (rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Set<Long> requirementIds = rows.stream()
                .map(row -> toLong(row.get("requirementId")))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (requirementIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Map<Long, Long> requirementLine = new HashMap<>();
        requirementMapper.selectBatchIds(requirementIds)
                .forEach(r -> requirementLine.put(r.getId(), r.getBusinessLineId()));

        BigDecimal hours = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            Long requirementId = toLong(row.get("requirementId"));
            Long lineId = requirementId == null ? null : requirementLine.get(requirementId);
            if (lineId != null && lineIds.contains(lineId) && row.get("hours") != null) {
                hours = hours.add(new BigDecimal(row.get("hours").toString()));
            }
        }
        if (hours.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return hours.divide(HOURS_PER_PERSON_MONTH, 4, RoundingMode.HALF_UP)
                .multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 其他成本（协力/服务器/其他）：归属月 ≤ 快照所在月（当年） */
    private BigDecimal otherCost(List<Long> lineIds, YearMonth currentMonth) {
        List<RevenueOtherCost> costs = otherCostMapper.selectList(new LambdaQueryWrapper<RevenueOtherCost>()
                .in(RevenueOtherCost::getBusinessLineId, lineIds)
                .ge(RevenueOtherCost::getYearMonth, currentMonth.getYear() + "-01")
                .le(RevenueOtherCost::getYearMonth, currentMonth.format(YM)));
        return costs.stream().map(c -> nullToZero(c.getAmountYuan())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------ 预警扫描

    /**
     * 环比增量 <= 0 → red；0 &lt; 增量 &lt; 周基准×yellowRatio → yellow。
     * 周基准 = 年度目标 ÷ 12 ÷ weeklyDivisor。无目标或规则停用时不产生 yellow。
     */
    private void scanAlerts(KpiWeeklySnapshot snapshot, int year, Long operatorUserId) {
        KpiAlertRule rule = alertRuleMapper.selectOne(new LambdaQueryWrapper<KpiAlertRule>()
                .eq(KpiAlertRule::getReportGroup, snapshot.getReportGroup()));
        if (rule == null) {
            rule = alertRuleMapper.selectOne(new LambdaQueryWrapper<KpiAlertRule>()
                    .isNull(KpiAlertRule::getReportGroup));
        }
        if (rule != null && !Integer.valueOf(1).equals(rule.getEnabled())) {
            return;
        }
        KpiTarget target = targetMapper.selectOne(new LambdaQueryWrapper<KpiTarget>()
                .eq(KpiTarget::getYear, year)
                .eq(KpiTarget::getReportGroup, snapshot.getReportGroup()));

        scanOne(snapshot, "revenue", snapshot.getWeekDeltaRevenue(),
                target == null ? null : target.getRevenueTarget(), rule, operatorUserId);
        scanOne(snapshot, "profit", snapshot.getWeekDeltaProfit(),
                target == null ? null : target.getProfitTarget(), rule, operatorUserId);
    }

    private void scanOne(KpiWeeklySnapshot snapshot, String metric, BigDecimal delta,
                         BigDecimal yearlyTarget, KpiAlertRule rule, Long operatorUserId) {
        String level = null;
        if (delta.compareTo(BigDecimal.ZERO) <= 0) {
            level = "red";
        } else if (rule != null && yearlyTarget != null && yearlyTarget.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal weeklyBase = yearlyTarget.divide(new BigDecimal("12"), 4, RoundingMode.HALF_UP)
                    .divide(rule.getWeeklyDivisor(), 4, RoundingMode.HALF_UP);
            BigDecimal threshold = weeklyBase.multiply(rule.getYellowRatio());
            if (delta.compareTo(threshold) < 0) {
                level = "yellow";
            }
        }
        if (level == null) {
            // 无异常：清理该单元格历史遗留的 pending 备注
            noteMapper.delete(new LambdaQueryWrapper<KpiDeviationNote>()
                    .eq(KpiDeviationNote::getSnapshotId, snapshot.getId())
                    .eq(KpiDeviationNote::getMetric, metric)
                    .eq(KpiDeviationNote::getStatus, "pending"));
            return;
        }
        KpiDeviationNote existing = noteMapper.selectOne(new LambdaQueryWrapper<KpiDeviationNote>()
                .eq(KpiDeviationNote::getSnapshotId, snapshot.getId())
                .eq(KpiDeviationNote::getMetric, metric));
        if (existing != null) {
            existing.setAlertLevel(level);
            noteMapper.updateById(existing);
            return;
        }
        KpiDeviationNote note = new KpiDeviationNote();
        note.setSnapshotId(snapshot.getId());
        note.setMetric(metric);
        note.setAlertLevel(level);
        note.setStatus("pending");
        note.setCreatedBy(operatorUserId);
        noteMapper.insert(note);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 校验分组名有效性 */
    public void assertValidGroup(String reportGroup) {
        if (!StringUtils.hasText(reportGroup) || !reportGroupLines().containsKey(reportGroup)) {
            throw new IllegalArgumentException("无效的报表行分组: " + reportGroup);
        }
    }
}
