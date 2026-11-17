package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bu.management.mapper.KbDocumentMapper;
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
 * 语料健康度的两个契约：按来源汇总的数字必须对得上总数，
 * 以及聚合查询失败时管理页仍能打开（返回空报告而不是 500）。
 */
@ExtendWith(MockitoExtension.class)
class KbHealthServiceTest {

    @Mock
    private KbDocumentMapper documentMapper;

    private KbHealthService service;

    @BeforeEach
    void setUp() {
        service = new KbHealthService(documentMapper);
    }

    @Test
    @DisplayName("按来源汇总：总量/启停/过期/最近更新时间都对得上")
    void aggregatesPerSource() {
        LocalDateTime latest = LocalDateTime.of(2026, 11, 10, 9, 30);
        List<Map<String, Object>> rows = List.of(
                Map.of("sourceType", "YUQUE", "total", 10L, "enabled", 8L,
                        "disabled", 2L, "stale", 3L,
                        "lastUpdatedAt", Timestamp.valueOf(latest)),
                // 不同驱动下 COUNT 可能回 BigDecimal，也要能收成 long
                Map.of("sourceType", "MANUAL", "total", new BigDecimal("4"), "enabled", 4L,
                        "disabled", 0L, "stale", 0L,
                        "lastUpdatedAt", Timestamp.valueOf(latest.minusDays(1))));
        when(documentMapper.statsBySource()).thenReturn(rows);
        when(documentMapper.countDuplicateChecksums()).thenReturn(2);

        KbHealthService.Report report = service.report();

        assertThat(report.total()).isEqualTo(14);
        assertThat(report.enabled()).isEqualTo(12);
        assertThat(report.disabled()).isEqualTo(2);
        assertThat(report.stale()).isEqualTo(3);
        assertThat(report.duplicates()).isEqualTo(2);
        assertThat(report.staleDays()).isEqualTo(180);
        assertThat(report.lastUpdatedAt()).isEqualTo(latest);
        assertThat(report.sources()).hasSize(2);
        assertThat(report.sources().get(0).sourceType()).isEqualTo("YUQUE");
        assertThat(report.sources().get(0).lastUpdatedAt()).isEqualTo(latest);
    }

    @Test
    @DisplayName("聚合失败：返回空报告，不让管理页整页打不开")
    void failureReturnsEmptyReport() {
        when(documentMapper.statsBySource()).thenThrow(new IllegalStateException("db down"));

        KbHealthService.Report report = service.report();

        assertThat(report.total()).isZero();
        assertThat(report.sources()).isEmpty();
        assertThat(report.lastUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("空库：全 0，不出现 NaN 或 null 列表")
    void emptyLibraryIsAllZero() {
        when(documentMapper.statsBySource()).thenReturn(List.of());
        when(documentMapper.countDuplicateChecksums()).thenReturn(0);

        KbHealthService.Report report = service.report();

        assertThat(report.total()).isZero();
        assertThat(report.enabled()).isZero();
        assertThat(report.sources()).isNotNull().isEmpty();
    }
}
