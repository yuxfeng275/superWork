package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bu.management.entity.BizLineProfitReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyReportKpiBuilderTest {

    @Test
    void proratesThreeMonthAverageCostAgainstCurrentRevenue() {
        List<BizLineProfitReport> rows = List.of(
                row("2026-07", "全域-全渠道-会员通", "300000", "120000"),
                row("2026-08", "全域-全渠道-会员通", "300000", "90000"),
                row("2026-09", "全域-全渠道-会员通", "300000", "60000"),
                row("2026-10", "全域-全渠道-会员通", "100000", "0"),
                row("2026-07", "全域-全渠道-全域云鹿Saas", "0", "-30000"),
                row("2026-10", "全域-全渠道-全域云鹿Saas", "50000", "0"));

        String text = WeeklyReportKpiBuilder.render(
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 10), rows);

        assertThat(text).doesNotContain("全域-全渠道-");
        assertThat(text).contains("截止 2026-10-10（当月已过 10/31 天）");
        assertThat(text).contains("2026-07、2026-08、2026-09");
        // 成本 18万、21万、24万，月均 21万；10/31 折算后毛利 = 10万 - 21万*10/31
        assertThat(text).contains("会员通：营收 10.00 万，毛利 3.23 万");
        assertThat(text).contains("全域云鹿Saas：营收 5.00 万，毛利 4.03 万");
        assertThat(text).contains("合计：营收 15.00 万，毛利 7.26 万");
    }

    @Test
    void usesFullAverageWhenTheReportMonthHasEnded() {
        BizLineProfitReport history = row("2026-08", "会员通", "200000", "100000");
        BizLineProfitReport current = row("2026-09", "会员通", "80000", "0");

        String text = WeeklyReportKpiBuilder.render(
                LocalDate.of(2026, 9, 14), LocalDate.of(2026, 10, 10),
                List.of(history, current));

        assertThat(text).contains("截止 2026-09-30。");
        assertThat(text).doesNotContain("已过");
        assertThat(text).contains("会员通：营收 8.00 万，毛利 -2.00 万");
    }

    @Test
    void explainsWhenCurrentMonthRevenueIsMissing() {
        String text = WeeklyReportKpiBuilder.render(
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 10),
                List.of(row("2026-09", "会员通", "100000", "40000")));

        assertThat(text).contains("当月营收尚未同步");
    }

    private static BizLineProfitReport row(String month, String name, String revenue, String gross) {
        BizLineProfitReport row = new BizLineProfitReport();
        row.setYearMonth(month);
        row.setWorktimeBusinessLineName(name);
        row.setRevenue(new BigDecimal(revenue));
        row.setGrossProfit(new BigDecimal(gross));
        return row;
    }
}
