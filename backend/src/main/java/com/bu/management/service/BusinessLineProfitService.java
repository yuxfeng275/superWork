package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BizLineProfitReport;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.WorktimeBusinessLineMapping;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.integration.WorktimeApiClient;
import com.bu.management.mapper.BizLineProfitReportMapper;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.WorktimeBusinessLineMappingMapper;
import com.bu.management.mapper.WorktimeSyncLogMapper;
import com.bu.management.vo.BizLineProfitReportVO;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务线月度利润报表：从工时系统同步（整月覆盖），按 业务线 × 月 查询并聚合 YTD。
 * 数据权威源为工时系统；本表为只读镜像，同步成功即整月替换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessLineProfitService {

    public static final String SYNC_TYPE = "bl_profit";
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final WorktimeApiClient apiClient;
    private final BizLineProfitReportMapper reportMapper;
    private final WorktimeBusinessLineMappingMapper blMappingMapper;
    private final BusinessLineMapper businessLineMapper;
    private final WorktimeSyncLogMapper syncLogMapper;

    // ==================== 同步 ====================

    /** 同步单个月份（整月覆盖） */
    public WorktimeSyncLog syncMonth(String yearMonth, String triggeredBy) {
        WorktimeSyncLog syncLog = newLog(yearMonth, triggeredBy);
        try {
            JsonNode data = apiClient.fetchBusinessLineMonthlyReport(yearMonth);
            JsonNode items = data.path("items");
            if (!items.isArray()) {
                throw new IllegalStateException("工时系统未返回报表行 items");
            }
            List<BizLineProfitReport> rows = new ArrayList<>();
            for (JsonNode item : items) {
                rows.add(toEntity(yearMonth, item));
            }
            replaceMonth(yearMonth, rows);
            finishLog(syncLog, "success", rows.size(), rows.size(), 0, "业务线利润月报同步完成");
        } catch (RuntimeException ex) {
            log.error("工时系统业务线利润报表同步失败 {}", yearMonth, ex);
            finishLog(syncLog, "failed", 0, 0, 0, ex.getMessage());
        }
        return syncLog;
    }

    /**
     * 同步某一年：从 year-01 逐月同步到 min(工时系统最新报表月, 当前月)。
     * 单月失败不中断整年，逐月记录同步日志。
     */
    public List<WorktimeSyncLog> syncYear(int year, String triggeredBy) {
        String latestMonth = resolveLatestMonth(year);
        List<WorktimeSyncLog> logs = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            String yearMonth = String.format("%d-%02d", year, m);
            if (yearMonth.compareTo(latestMonth) > 0) {
                break;
            }
            logs.add(syncMonth(yearMonth, triggeredBy));
        }
        return logs;
    }

    /** 工时系统最新报表月，封顶为当前月；获取失败时按自然月兜底 */
    private String resolveLatestMonth(int year) {
        String currentMonth = LocalDate.now().format(MONTH_FMT);
        String yearEnd = year + "-12";
        String cap = currentMonth.compareTo(yearEnd) < 0 ? currentMonth : yearEnd;
        try {
            JsonNode data = apiClient.fetchBusinessLineReportLatestMonth();
            String latest = data.isTextual() ? data.asText() : data.path("year_month").asText(null);
            if (StringUtils.hasText(latest) && latest.matches("\\d{4}-\\d{2}")) {
                // 同年时以工时系统最新月为准（仍封顶当前月）；跨年请求只关心年内
                if (latest.startsWith(year + "-")) {
                    return latest.compareTo(cap) < 0 ? latest : cap;
                }
                if (latest.compareTo(year + "-01") < 0) {
                    // 工时系统最新月早于该年 → 该年无数据
                    return year + "-00";
                }
                return cap;
            }
        } catch (RuntimeException ex) {
            log.warn("获取工时系统最新报表月失败，按当前月兜底: {}", ex.getMessage());
        }
        return cap;
    }

    private void replaceMonth(String yearMonth, List<BizLineProfitReport> rows) {
        reportMapper.delete(new LambdaQueryWrapper<BizLineProfitReport>()
                .eq(BizLineProfitReport::getYearMonth, yearMonth));
        LocalDateTime now = LocalDateTime.now();
        for (BizLineProfitReport row : rows) {
            row.setSyncedAt(now);
            reportMapper.insert(row);
        }
    }

    private BizLineProfitReport toEntity(String yearMonth, JsonNode item) {
        BizLineProfitReport row = new BizLineProfitReport();
        row.setYearMonth(yearMonth);
        Long wtBlId = item.path("business_line_id").isNumber() ? item.path("business_line_id").asLong() : null;
        row.setWorktimeBusinessLineId(wtBlId);
        row.setWorktimeBusinessLineName(text(item, "business_line_name"));
        row.setGroupName(text(item, "group_name"));
        row.setBusinessLineId(resolveBusinessLineId(wtBlId, row.getWorktimeBusinessLineName()));
        row.setRevenue(decimal(item, "revenue"));
        row.setSmsCost(decimal(item, "sms_cost"));
        row.setDirectCost(decimal(item, "direct_cost"));
        row.setPlatformFee(decimal(item, "platform_fee"));
        row.setCompensation(decimal(item, "compensation"));
        row.setOutsourcing(decimal(item, "outsourcing"));
        row.setSoftwareGift(decimal(item, "software_gift"));
        row.setTotalHours(decimal(item, "total_hours"));
        row.setHoursRatio(decimal(item, "hours_ratio"));
        row.setExpense1(decimal(item, "expense_1"));
        row.setLaborCost1(decimal(item, "labor_cost_1"));
        row.setGrossProfit(decimal(item, "gross_profit"));
        row.setGrossProfitRate(decimal(item, "gross_profit_rate"));
        row.setMarketingCost(decimal(item, "marketing_cost"));
        row.setLaborCost2Sales(decimal(item, "labor_cost_2_sales"));
        row.setLaborCost3Backend(decimal(item, "labor_cost_3_backend"));
        row.setLaborCost3Tech(decimal(item, "labor_cost_3_tech"));
        row.setLaborCost3Rd(decimal(item, "labor_cost_3_rd"));
        row.setExpense2(decimal(item, "expense_2"));
        row.setNetProfit(decimal(item, "net_profit"));
        row.setNetProfitRate(decimal(item, "net_profit_rate"));
        return row;
    }

    /** 业务线映射：优先工时系统业务线 ID 映射表，兜底本系统业务线名称精确匹配 */
    private Long resolveBusinessLineId(Long wtBlId, String wtBlName) {
        if (wtBlId != null) {
            WorktimeBusinessLineMapping mapping = blMappingMapper.selectOne(
                    new LambdaQueryWrapper<WorktimeBusinessLineMapping>()
                            .eq(WorktimeBusinessLineMapping::getWorktimeBusinessLineId, wtBlId)
                            .last("LIMIT 1"));
            if (mapping != null) {
                return mapping.getBusinessLineId();
            }
        }
        if (StringUtils.hasText(wtBlName)) {
            BusinessLine line = businessLineMapper.selectOne(new LambdaQueryWrapper<BusinessLine>()
                    .eq(BusinessLine::getName, wtBlName)
                    .last("LIMIT 1"));
            if (line != null) {
                return line.getId();
            }
        }
        return null;
    }

    // ==================== 查询聚合 ====================

    /** 年度报表：业务线分组（月行 + YTD 小计）+ 全表总计 */
    public BizLineProfitReportVO queryYear(int year) {
        List<BizLineProfitReport> rows = reportMapper.selectList(
                new LambdaQueryWrapper<BizLineProfitReport>()
                        .likeRight(BizLineProfitReport::getYearMonth, year + "-")
                        .orderByAsc(BizLineProfitReport::getYearMonth));

        Map<String, List<BizLineProfitReport>> byLine = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing(BizLineProfitReport::getWorktimeBusinessLineName,
                        Comparator.nullsLast(String::compareTo)))
                .forEach(row -> byLine.computeIfAbsent(
                        row.getWorktimeBusinessLineName() == null ? "未知" : row.getWorktimeBusinessLineName(),
                        k -> new ArrayList<>()).add(row));

        BizLineProfitReportVO vo = new BizLineProfitReportVO();
        vo.setYear(year);
        List<BizLineProfitReportVO.LineGroup> lines = new ArrayList<>();
        BizLineProfitReport total = emptyRow("合计", null);
        for (Map.Entry<String, List<BizLineProfitReport>> entry : byLine.entrySet()) {
            List<BizLineProfitReport> months = entry.getValue();
            BizLineProfitReportVO.LineGroup group = new BizLineProfitReportVO.LineGroup();
            group.setBusinessLineId(months.get(0).getBusinessLineId());
            group.setBusinessLineName(entry.getKey());
            group.setGroupName(months.get(0).getGroupName());
            group.setMonths(months);
            group.setYtd(aggregate(entry.getKey(), months));
            accumulate(total, group.getYtd());
            lines.add(group);
        }
        finalizeRates(total);
        vo.setLines(lines);
        vo.setTotalYtd(total);
        return vo;
    }

    /** YTD 聚合：金额/工时求和，比率按合计重算（毛利率=毛利/营收，净利率=净利/营收） */
    private BizLineProfitReport aggregate(String name, List<BizLineProfitReport> months) {
        BizLineProfitReport ytd = emptyRow(name, "YTD");
        months.forEach(month -> accumulate(ytd, month));
        finalizeRates(ytd);
        return ytd;
    }

    private BizLineProfitReport emptyRow(String name, String yearMonth) {
        BizLineProfitReport row = new BizLineProfitReport();
        row.setWorktimeBusinessLineName(name);
        row.setYearMonth(yearMonth);
        return row;
    }

    private void accumulate(BizLineProfitReport acc, BizLineProfitReport row) {
        acc.setRevenue(add(acc.getRevenue(), row.getRevenue()));
        acc.setSmsCost(add(acc.getSmsCost(), row.getSmsCost()));
        acc.setDirectCost(add(acc.getDirectCost(), row.getDirectCost()));
        acc.setPlatformFee(add(acc.getPlatformFee(), row.getPlatformFee()));
        acc.setCompensation(add(acc.getCompensation(), row.getCompensation()));
        acc.setOutsourcing(add(acc.getOutsourcing(), row.getOutsourcing()));
        acc.setSoftwareGift(add(acc.getSoftwareGift(), row.getSoftwareGift()));
        acc.setTotalHours(add(acc.getTotalHours(), row.getTotalHours()));
        acc.setExpense1(add(acc.getExpense1(), row.getExpense1()));
        acc.setLaborCost1(add(acc.getLaborCost1(), row.getLaborCost1()));
        acc.setGrossProfit(add(acc.getGrossProfit(), row.getGrossProfit()));
        acc.setMarketingCost(add(acc.getMarketingCost(), row.getMarketingCost()));
        acc.setLaborCost2Sales(add(acc.getLaborCost2Sales(), row.getLaborCost2Sales()));
        acc.setLaborCost3Backend(add(acc.getLaborCost3Backend(), row.getLaborCost3Backend()));
        acc.setLaborCost3Tech(add(acc.getLaborCost3Tech(), row.getLaborCost3Tech()));
        acc.setLaborCost3Rd(add(acc.getLaborCost3Rd(), row.getLaborCost3Rd()));
        acc.setExpense2(add(acc.getExpense2(), row.getExpense2()));
        acc.setNetProfit(add(acc.getNetProfit(), row.getNetProfit()));
    }

    private void finalizeRates(BizLineProfitReport row) {
        row.setGrossProfitRate(rate(row.getGrossProfit(), row.getRevenue()));
        row.setNetProfitRate(rate(row.getNetProfit(), row.getRevenue()));
    }

    private BigDecimal rate(BigDecimal part, BigDecimal total) {
        if (part == null || total == null || total.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal add(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.add(b);
    }

    // ==================== 同步日志 ====================

    public List<WorktimeSyncLog> recentSyncLogs(int limit) {
        return syncLogMapper.selectList(new LambdaQueryWrapper<WorktimeSyncLog>()
                .eq(WorktimeSyncLog::getSyncType, SYNC_TYPE)
                .orderByDesc(WorktimeSyncLog::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50))));
    }

    private WorktimeSyncLog newLog(String scope, String triggeredBy) {
        WorktimeSyncLog syncLog = new WorktimeSyncLog();
        syncLog.setSyncType(SYNC_TYPE);
        syncLog.setScope(scope);
        syncLog.setStatus("running");
        syncLog.setTriggeredBy(triggeredBy == null ? "manual" : triggeredBy);
        syncLog.setStartedAt(LocalDateTime.now());
        syncLogMapper.insert(syncLog);
        return syncLog;
    }

    private void finishLog(WorktimeSyncLog syncLog, String status, int total, int upserted, int pending, String message) {
        syncLog.setStatus(status);
        syncLog.setTotalCount(total);
        syncLog.setUpsertCount(upserted);
        syncLog.setPendingCount(pending);
        syncLog.setMessage(message == null || message.length() <= 1000 ? message : message.substring(0, 1000));
        syncLog.setFinishedAt(LocalDateTime.now());
        syncLogMapper.updateById(syncLog);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
