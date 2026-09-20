package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.WeeklyReport;
import com.bu.management.integration.YuqueMcpClient;
import com.bu.management.mapper.WeeklyReportMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeeklyReportServiceTest {

    @Mock private WeeklyReportMapper reportMapper;
    @Mock private YuqueMcpClient yuqueClient;
    @Mock private SystemConfigService configService;

    @InjectMocks private WeeklyReportService service;

    private WeeklyReport report;

    @BeforeEach
    void setUp() {
        report = new WeeklyReport();
        report.setId(3L);
        report.setWeekStartDate(LocalDate.of(2026, 9, 14));
        report.setPeriodEndDate(LocalDate.of(2026, 9, 18));
        report.setYuqueDocUrl("https://lucidata.yuque.com/vuntcs/cf_records/abc");
        report.setStatus(WeeklyReport.STATUS_PUBLISHED);
    }

    @Test
    void generationPromptAsksForHighlightsNotCatalog() {
        String prompt = service.generationSystemPrompt();
        assertThat(prompt).contains("不是工作流水账");
        assertThat(prompt).contains("全篇最多 5 条");
        assertThat(prompt).contains("600 字");
        assertThat(prompt).doesNotContain("千人千面");
        assertThat(prompt).doesNotContain("各最多 4 条");
    }

    @Test
    void distillFactsKeepsRisksAndDropsRoutine() {
        java.util.Map<String, Object> facts = new java.util.LinkedHashMap<>();
        facts.put("finance", java.util.Map.of("newContractAmount", 1));
        facts.put("lastWeekReport", java.util.Map.of("exists", false));
        facts.put("keyMatters", java.util.List.of(
                java.util.Map.of(
                        "title", "皇家积分切换",
                        "priority", "P0",
                        "status", "有风险",
                        "currentWeek", true,
                        "progressSummary", "订单总数不一致，差额较大"),
                java.util.Map.of(
                        "title", "日常巡检",
                        "priority", "P2",
                        "status", "推进中",
                        "currentWeek", false,
                        "progressSummary", "按原计划继续")));

        java.util.Map<String, Object> distilled = service.distillFactsForGeneration(facts);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> matters =
                (java.util.List<java.util.Map<String, Object>>) distilled.get("keyMatters");
        assertThat(matters).hasSize(1);
        assertThat(matters.get(0).get("title")).isEqualTo("皇家积分切换");
    }

    @Test
    void fillSheetMarksDoneWithoutWritingWhenManual() {
        when(reportMapper.selectById(3L)).thenReturn(report);
        when(configService.getValue(eq("weekly-report"), eq("sheet.mode"), any()))
                .thenReturn("MANUAL");

        WeeklyReport updated = service.fillSheet(3L);

        assertThat(updated.getSheetSyncStatus()).isEqualTo("MANUAL_DONE");
        assertThat(updated.getSheetSyncedAt()).isNotNull();
        verify(yuqueClient, never()).writeSheetRow(any(), any(), any(), any(), any(), any(), any(), any());
        verify(reportMapper).updateById(report);
    }

    @Test
    void fillSheetWritesMinutesLinkWhenApiMode() {
        when(reportMapper.selectById(3L)).thenReturn(report);
        when(configService.getValue(eq("weekly-report"), eq("sheet.team-name"), any()))
                .thenReturn("电商业务BU");
        when(configService.getValue(eq("weekly-report"), eq("sheet.sheet-name"), any()))
                .thenReturn("电商业务");
        when(configService.getValue(eq("weekly-report"), eq("sheet.doc-slug"), any()))
                .thenReturn("staff-qvc012/mghdgg/tyavbayo9ir7tyrk");
        when(configService.getValue(eq("weekly-report"), eq("sheet.mode"), any()))
                .thenReturn("API");
        when(yuqueClient.writeSheetRow(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new YuqueMcpClient.SheetWriteResult(true, "ok"));

        WeeklyReport updated = service.fillSheet(3L);

        assertThat(updated.getSheetSyncStatus()).isEqualTo("MANUAL_DONE");
        verify(yuqueClient).writeSheetRow(
                eq("API"),
                any(),
                any(),
                eq("staff-qvc012/mghdgg/tyavbayo9ir7tyrk"),
                eq("电商业务"),
                eq("09.14-09.18"),
                eq("电商业务BU"),
                eq("https://lucidata.yuque.com/vuntcs/cf_records/abc"));
    }

    @Test
    void fillSheetRequiresPublishedMinutes() {
        report.setYuqueDocUrl(null);
        when(reportMapper.selectById(3L)).thenReturn(report);

        assertThatThrownBy(() -> service.fillSheet(3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("语雀");
    }
}
