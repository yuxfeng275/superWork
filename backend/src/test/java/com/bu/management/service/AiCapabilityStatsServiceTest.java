package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.bu.management.mapper.AiCapabilityCallMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 统计口径的三个契约：比率分母为 0 时返回 0、成功/失败按同一批行算、
 * 聚合失败时看板仍能打开（空报告而不是 500）。
 */
@ExtendWith(MockitoExtension.class)
class AiCapabilityStatsServiceTest {

    @Mock
    private AiCapabilityCallMapper callMapper;

    private AiCapabilityStatsService service;

    @BeforeEach
    void setUp() {
        service = new AiCapabilityStatsService(callMapper);
    }

    @Test
    @DisplayName("按能力汇总：成功率/超时率/门禁拒绝率用同一分母")
    void aggregatesRates() {
        LocalDateTime last = LocalDateTime.of(2026, 11, 17, 10, 0);
        when(callMapper.statsSince(any())).thenReturn(List.of(
                Map.of("capabilityId", "query_my_tasks", "calls", 10L, "okCalls", 7L,
                        "failedCalls", 3L, "timeouts", 1L, "gateDenied", 2L,
                        "avgLatencyMs", 120L, "lastCalledAt", Timestamp.valueOf(last)),
                Map.of("capabilityId", "search_knowledge", "calls", new BigDecimal("4"),
                        "okCalls", 4L, "failedCalls", 0L, "timeouts", 0L, "gateDenied", 0L,
                        "avgLatencyMs", new BigDecimal("35.5"), "lastCalledAt", Timestamp.valueOf(last))));

        AiCapabilityStatsService.Report report = service.report(7);

        assertThat(report.days()).isEqualTo(7);
        assertThat(report.totalCalls()).isEqualTo(14);
        assertThat(report.capabilities()).hasSize(2);
        AiCapabilityStatsService.CapabilityStat first = report.capabilities().get(0);
        assertThat(first.capabilityId()).isEqualTo("query_my_tasks");
        assertThat(first.successRate()).isEqualTo(0.7d);
        assertThat(first.timeoutRate()).isEqualTo(0.1d);
        assertThat(first.gateDeniedRate()).isEqualTo(0.2d);
        assertThat(first.avgLatencyMs()).isEqualTo(120L);
        assertThat(first.lastCalledAt()).isEqualTo(last);
        assertThat(report.capabilities().get(1).avgLatencyMs()).isEqualTo(35L);
    }

    @Test
    @DisplayName("窗口天数被夹在 1..90；0 取默认 7")
    void clampsWindow() {
        when(callMapper.statsSince(any())).thenReturn(List.of());

        assertThat(service.report(0).days()).isEqualTo(7);
        assertThat(service.report(500).days()).isEqualTo(90);
        assertThat(service.report(null).days()).isEqualTo(7);
    }

    @Test
    @DisplayName("聚合失败：返回空报告，看板不 500")
    void failureReturnsEmptyReport() {
        when(callMapper.statsSince(any())).thenThrow(new IllegalStateException("db down"));

        AiCapabilityStatsService.Report report = service.report(7);

        assertThat(report.totalCalls()).isZero();
        assertThat(report.capabilities()).isEmpty();
    }
}
