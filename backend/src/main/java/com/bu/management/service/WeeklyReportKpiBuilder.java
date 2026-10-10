package com.bu.management.service;

import com.bu.management.entity.BizLineProfitReport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 周报 KPI：当月营收取业务线利润表，成本取此前 3 个自然月的月均考核成本。
 * 月份未结束时，按已过天数把月均成本折成截止当日的成本，再算毛利。
 * 考核成本 = 营收 − 考核毛利；没有毛利字段时退回各项成本之和。
 */
public final class WeeklyReportKpiBuilder {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final String NAME_PREFIX = "全域-全渠道-";
    private static final BigDecimal WAN = new BigDecimal("10000");

    private WeeklyReportKpiBuilder() {
    }

    public static String render(LocalDate weekStart, LocalDate today, List<BizLineProfitReport> rows) {
        YearMonth month = YearMonth.from(weekStart);
        LocalDate asOf = month.equals(YearMonth.from(today))
                ? today
                : (month.isBefore(YearMonth.from(today)) ? month.atEndOfMonth() : month.atDay(1));
        List<YearMonth> history = List.of(month.minusMonths(3), month.minusMonths(2), month.minusMonths(1));
        Map<String, Line> lines = new LinkedHashMap<>();
        if (rows != null) {
            for (BizLineProfitReport row : rows) {
                if (row == null || row.getYearMonth() == null) {
                    continue;
                }
                YearMonth rowMonth = YearMonth.parse(row.getYearMonth());
                if (!rowMonth.equals(month) && !history.contains(rowMonth)) {
                    continue;
                }
                String rawName = row.getWorktimeBusinessLineName() == null
                        ? "未知业务线" : row.getWorktimeBusinessLineName();
                Line line = lines.computeIfAbsent(rawName, Line::new);
                if (rowMonth.equals(month)) {
                    line.current = true;
                    line.revenue = line.revenue.add(nz(row.getRevenue()));
                } else {
                    BigDecimal cost = assessmentCost(row);
                    if (cost != null) {
                        line.costSum = line.costSum.add(cost);
                        line.costMonths += 1;
                    }
                }
            }
        }
        List<Line> visible = lines.values().stream()
                .filter(line -> line.current || line.costMonths > 0)
                .sorted(Comparator.comparing(line -> displayName(line.rawName)))
                .toList();
        if (visible.stream().noneMatch(line -> line.current)) {
            return "当月营收尚未同步到业务线利润表，无法按业务线计算截止 "
                    + asOf + " 的毛利。";
        }
        int day = asOf.getDayOfMonth();
        int length = asOf.lengthOfMonth();
        BigDecimal ratio = BigDecimal.valueOf(day)
                .divide(BigDecimal.valueOf(length), 8, RoundingMode.HALF_UP);
        StringBuilder text = new StringBuilder();
        text.append("截止 ").append(asOf);
        if (day < length) {
            text.append("（当月已过 ").append(day).append('/').append(length).append(" 天）");
        }
        text.append("。营收取 ").append(month.format(MONTH_FMT))
                .append(" 业务线利润；成本取 ");
        text.append(history.get(0).format(MONTH_FMT)).append('、')
                .append(history.get(1).format(MONTH_FMT)).append('、')
                .append(history.get(2).format(MONTH_FMT));
        text.append(day < length ? " 的月均考核成本，并按已过天数折算。\n" : " 的月均考核成本。\n");
        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalGross = BigDecimal.ZERO;
        List<String> pending = new ArrayList<>();
        for (Line line : visible) {
            String name = displayName(line.rawName);
            if (!line.current) {
                pending.add(name);
                continue;
            }
            BigDecimal avg = line.costMonths == 0
                    ? BigDecimal.ZERO
                    : line.costSum.divide(BigDecimal.valueOf(line.costMonths), 2, RoundingMode.HALF_UP);
            BigDecimal cost = avg.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            BigDecimal gross = line.revenue.subtract(cost);
            totalRevenue = totalRevenue.add(line.revenue);
            totalGross = totalGross.add(gross);
            text.append(name).append("：营收 ").append(wan(line.revenue))
                    .append(" 万，毛利 ").append(wan(gross)).append(" 万\n");
        }
        text.append("合计：营收 ").append(wan(totalRevenue))
                .append(" 万，毛利 ").append(wan(totalGross)).append(" 万");
        if (!pending.isEmpty()) {
            text.append("\n当月营收未同步：").append(String.join("、", pending));
        }
        return text.toString().trim();
    }

    static BigDecimal assessmentCost(BizLineProfitReport row) {
        if (row.getRevenue() != null && row.getGrossProfit() != null) {
            return row.getRevenue().subtract(row.getGrossProfit());
        }
        if (row.getSmsCost() == null && row.getDirectCost() == null && row.getPlatformFee() == null
                && row.getCompensation() == null && row.getOutsourcing() == null
                && row.getSoftwareGift() == null && row.getLaborCost1() == null) {
            return null;
        }
        return nz(row.getSmsCost()).add(nz(row.getDirectCost())).add(nz(row.getPlatformFee()))
                .add(nz(row.getCompensation())).add(nz(row.getOutsourcing()))
                .add(nz(row.getSoftwareGift())).add(nz(row.getLaborCost1()));
    }

    private static String displayName(String name) {
        return name.startsWith(NAME_PREFIX) ? name.substring(NAME_PREFIX.length()) : name;
    }

    private static String wan(BigDecimal yuan) {
        return yuan.divide(WAN, 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static final class Line {
        private final String rawName;
        private boolean current;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal costSum = BigDecimal.ZERO;
        private int costMonths;

        private Line(String rawName) {
            this.rawName = rawName;
        }
    }
}
