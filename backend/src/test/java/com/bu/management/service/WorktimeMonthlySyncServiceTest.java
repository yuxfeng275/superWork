package com.bu.management.service;

import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.integration.WorktimeApiClient;
import com.bu.management.mapper.RevenueMonthCloseMapper;
import com.bu.management.mapper.WorktimeSyncLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 月度同步编排：forceMonth 必须在 available-months 之外也能强制重拉该月
 * （工时系统该列表只反映当前确认周期，历史月份会从列表消失但导出/成本接口仍可用）。
 */
@ExtendWith(MockitoExtension.class)
class WorktimeMonthlySyncServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private WorktimeApiClient apiClient;
    @Mock
    private RevenueImportService revenueImportService;
    @Mock
    private RevenueMappingResolver mappingResolver;
    @Mock
    private WorktimeSyncLogMapper syncLogMapper;
    @Mock
    private RevenueMonthCloseMapper monthCloseMapper;

    private WorktimeMonthlySyncService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new WorktimeMonthlySyncService(apiClient, revenueImportService, mappingResolver,
                syncLogMapper, monthCloseMapper);
        // 账号可见业务线（导出工时明细用）
        when(apiClient.testConnection()).thenReturn(MAPPER.readTree(
                "{\"business_lines\":[{\"id\":119},{\"id\":120}]}"));
        lenient().when(apiClient.downloadWorklogBlDetail(any(), anyString())).thenReturn(new byte[]{1, 2, 3});
        lenient().when(apiClient.fetchAllCostByProject(anyString(), anyString())).thenReturn(List.of());
        lenient().when(revenueImportService.importWorklogStream(any(InputStream.class), anyString(),
                anyString(), isNull())).thenReturn(result());
        lenient().when(revenueImportService.saveCostEntries(any(), anyString(), eq("WORKTIME"), isNull()))
                .thenReturn(result());
        lenient().when(monthCloseMapper.selectCount(any())).thenReturn(0L);
        lenient().when(syncLogMapper.selectCount(any())).thenReturn(0L);
    }

    private RevenueImportResultVO result() {
        RevenueImportResultVO vo = new RevenueImportResultVO();
        vo.setTotalCount(1);
        vo.setSuccessCount(1);
        return vo;
    }

    @Test
    @DisplayName("forceMonth 不在 available-months（只剩最新月）时仍强制重拉该月工时+成本")
    void forceMonthOutsideAvailableMonthsStillSyncs() throws Exception {
        when(apiClient.fetchAvailableMonths()).thenReturn(MAPPER.readTree(
                "[{\"year_month\":\"2026-10\",\"status\":null}]"));

        List<WorktimeSyncLog> logs = service.syncConfirmedMonths("2026-09", "manual");

        assertThat(logs).extracting(WorktimeSyncLog::getSyncType).containsExactly("worklog", "cost");
        assertThat(logs).allMatch(l -> "2026-09".equals(l.getScope()));
        assertThat(logs).allMatch(l -> "success".equals(l.getStatus()));
        verify(revenueImportService).importWorklogStream(any(InputStream.class), anyString(),
                eq("2026-09"), isNull());
        verify(apiClient).fetchAllCostByProject("2026-09", "2026-09");
    }

    @Test
    @DisplayName("available-months 非数组时，forceMonth 仍强制重拉")
    void forceMonthWhenAvailableMonthsNotArray() throws Exception {
        when(apiClient.fetchAvailableMonths()).thenReturn(MAPPER.readTree("{}"));

        List<WorktimeSyncLog> logs = service.syncConfirmedMonths("2026-09", "manual");

        assertThat(logs).extracting(WorktimeSyncLog::getSyncType).containsExactly("worklog", "cost");
    }

    @Test
    @DisplayName("forceMonth 命中已完结月：按既有规则跳过，不重拉")
    void forceMonthClosedIsSkipped() throws Exception {
        when(apiClient.fetchAvailableMonths()).thenReturn(MAPPER.readTree("[]"));
        when(monthCloseMapper.selectCount(any())).thenReturn(1L);

        List<WorktimeSyncLog> logs = service.syncConfirmedMonths("2026-08", "manual");

        assertThat(logs).isEmpty();
        verify(revenueImportService, never()).importWorklogStream(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("forceMonth 在 available-months 内：命中即强制重拉，不重复补拉")
    void forceMonthInAvailableMonthsSyncedOnce() throws Exception {
        when(apiClient.fetchAvailableMonths()).thenReturn(MAPPER.readTree(
                "[{\"year_month\":\"2026-09\",\"status\":null}]"));

        List<WorktimeSyncLog> logs = service.syncConfirmedMonths("2026-09", "manual");

        assertThat(logs).extracting(WorktimeSyncLog::getSyncType).containsExactly("worklog", "cost");
        verify(apiClient).fetchAllCostByProject("2026-09", "2026-09");
    }

    @Test
    @DisplayName("自动扫描（无 forceMonth）：仅 confirmed 且未同步的月份被拉取")
    void autoScanOnlyConfirmedMonths() throws Exception {
        when(apiClient.fetchAvailableMonths()).thenReturn(MAPPER.readTree(
                "[{\"year_month\":\"2026-08\",\"status\":\"confirmed\"},"
                        + "{\"year_month\":\"2026-09\",\"status\":null}]"));

        List<WorktimeSyncLog> logs = service.syncConfirmedMonths(null, "schedule");

        assertThat(logs).extracting(WorktimeSyncLog::getScope).containsOnly("2026-08");
        verify(apiClient).fetchAllCostByProject("2026-08", "2026-08");
    }
}
