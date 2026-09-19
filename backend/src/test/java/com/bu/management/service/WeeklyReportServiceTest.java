package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    void generationPromptCapsLengthAndBansFiller() {
        String prompt = service.generationSystemPrompt();
        assertThat(prompt).contains("禁止套话");
        assertThat(prompt).contains("不超过 40 字");
        assertThat(prompt).contains("1500 字");
        assertThat(prompt).doesNotContain("千人千面");
    }

    @Test
    void fillSheetWritesMinutesLinkThenMarksDone() {
        when(reportMapper.selectById(3L)).thenReturn(report);
        when(configService.getValue(eq("weekly-report"), eq("sheet.team-name"), any()))
                .thenReturn("电商业务BU");
        when(configService.getValue(eq("weekly-report"), eq("sheet.sheet-name"), any()))
                .thenReturn("电商业务");
        when(configService.getValue(eq("weekly-report"), eq("sheet.doc-slug"), any()))
                .thenReturn("staff-qvc012/mghdgg/tyavbayo9ir7tyrk");
        when(configService.getValue(eq("weekly-report"), eq("sheet.mode"), any()))
                .thenReturn("MANUAL");
        when(yuqueClient.writeSheetRow(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new YuqueMcpClient.SheetWriteResult(true, "ok"));

        WeeklyReport updated = service.fillSheet(3L);

        assertThat(updated.getSheetSyncStatus()).isEqualTo("MANUAL_DONE");
        assertThat(updated.getSheetSyncedAt()).isNotNull();
        verify(yuqueClient).writeSheetRow(
                eq("MANUAL"),
                any(),
                any(),
                eq("staff-qvc012/mghdgg/tyavbayo9ir7tyrk"),
                eq("电商业务"),
                eq("09.14-09.18"),
                eq("电商业务BU"),
                eq("https://lucidata.yuque.com/vuntcs/cf_records/abc"));
        verify(reportMapper).updateById(report);
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
