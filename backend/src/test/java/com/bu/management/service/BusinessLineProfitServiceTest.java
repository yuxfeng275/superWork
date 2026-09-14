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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 业务线利润报表：同步落库（整月覆盖）与 YTD 聚合口径。
 */
@ExtendWith(MockitoExtension.class)
class BusinessLineProfitServiceTest {

    @Mock
    private WorktimeApiClient apiClient;
    @Mock
    private BizLineProfitReportMapper reportMapper;
    @Mock
    private WorktimeBusinessLineMappingMapper blMappingMapper;
    @Mock
    private BusinessLineMapper businessLineMapper;
    @Mock
    private WorktimeSyncLogMapper syncLogMapper;

    private BusinessLineProfitService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new BusinessLineProfitService(apiClient, reportMapper, blMappingMapper, businessLineMapper, syncLogMapper);
    }

    private BizLineProfitReport row(String month, String name, String revenue, String gross, String net, String hours) {
        BizLineProfitReport row = new BizLineProfitReport();
        row.setYearMonth(month);
        row.setWorktimeBusinessLineName(name);
        row.setRevenue(revenue == null ? null : new BigDecimal(revenue));
        row.setGrossProfit(gross == null ? null : new BigDecimal(gross));
        row.setNetProfit(net == null ? null : new BigDecimal(net));
        row.setTotalHours(hours == null ? null : new BigDecimal(hours));
        return row;
    }

    @Test
    @DisplayName("同步单月：items 映射落库且整月覆盖，映射表优先命中本系统业务线")
    void syncMonthReplacesMonth() throws Exception {
        String payload = """
                {"items":[
                  {"business_line_id":121,"business_line_name":"全域-二象限-AI服务","group_name":"二象限",
                   "revenue":706840,"gross_profit":415235,"gross_profit_rate":58.7,
                   "net_profit":300000,"net_profit_rate":42.4,"total_hours":11.79}
                ]}
                """;
        when(apiClient.fetchBusinessLineMonthlyReport("2026-01")).thenReturn(objectMapper.readTree(payload));
        WorktimeBusinessLineMapping mapping = new WorktimeBusinessLineMapping();
        mapping.setBusinessLineId(7L);
        when(blMappingMapper.selectOne(any())).thenReturn(mapping);

        WorktimeSyncLog log = service.syncMonth("2026-01", "manual");

        assertThat(log.getStatus()).isEqualTo("success");
        assertThat(log.getTotalCount()).isEqualTo(1);
        verify(reportMapper).delete(any(LambdaQueryWrapper.class));
        ArgumentCaptor<BizLineProfitReport> captor = ArgumentCaptor.forClass(BizLineProfitReport.class);
        verify(reportMapper).insert(captor.capture());
        BizLineProfitReport saved = captor.getValue();
        assertThat(saved.getBusinessLineId()).isEqualTo(7L);
        assertThat(saved.getWorktimeBusinessLineName()).isEqualTo("全域-二象限-AI服务");
        assertThat(saved.getRevenue()).isEqualByComparingTo("706840");
        assertThat(saved.getGrossProfit()).isEqualByComparingTo("415235");
        assertThat(saved.getSyncedAt()).isNotNull();
    }

    @Test
    @DisplayName("同步单月：映射表未命中时按名称兜底匹配本系统业务线")
    void syncMonthFallsBackToNameMatch() throws Exception {
        String payload = """
                {"items":[{"business_line_id":999,"business_line_name":"会员通","revenue":100}]}
                """;
        when(apiClient.fetchBusinessLineMonthlyReport("2026-02")).thenReturn(objectMapper.readTree(payload));
        when(blMappingMapper.selectOne(any())).thenReturn(null);
        BusinessLine line = new BusinessLine();
        line.setId(3L);
        line.setName("会员通");
        when(businessLineMapper.selectOne(any())).thenReturn(line);

        service.syncMonth("2026-02", "manual");

        ArgumentCaptor<BizLineProfitReport> captor = ArgumentCaptor.forClass(BizLineProfitReport.class);
        verify(reportMapper).insert(captor.capture());
        assertThat(captor.getValue().getBusinessLineId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("同步失败：异常写入同步日志且不抛错")
    void syncMonthFailureLogged() {
        when(apiClient.fetchBusinessLineMonthlyReport("2026-03"))
                .thenThrow(new IllegalStateException("工时系统接口返回错误(500): boom"));

        WorktimeSyncLog log = service.syncMonth("2026-03", "manual");

        assertThat(log.getStatus()).isEqualTo("failed");
        assertThat(log.getMessage()).contains("boom");
        verify(reportMapper, never()).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("YTD 聚合：金额求和、比率按合计重算、总计行汇总各业务线")
    void queryYearAggregatesYtd() {
        when(reportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                row("2026-01", "会员通", "100", "40", "30", "10"),
                row("2026-02", "会员通", "300", "60", "50", "20"),
                row("2026-01", "SAAS", "100", "-10", "-20", "5")
        ));

        BizLineProfitReportVO vo = service.queryYear(2026);

        assertThat(vo.getLines()).hasSize(2);
        BizLineProfitReportVO.LineGroup hyt = vo.getLines().stream()
                .filter(l -> l.getBusinessLineName().equals("会员通")).findFirst().orElseThrow();
        assertThat(hyt.getMonths()).hasSize(2);
        assertThat(hyt.getYtd().getRevenue()).isEqualByComparingTo("400");
        assertThat(hyt.getYtd().getGrossProfit()).isEqualByComparingTo("100");
        assertThat(hyt.getYtd().getTotalHours()).isEqualByComparingTo("30");
        // 毛利率 = 100/400 = 25%
        assertThat(hyt.getYtd().getGrossProfitRate()).isEqualByComparingTo("25.00");
        // 净利率 = 80/400 = 20%
        assertThat(hyt.getYtd().getNetProfitRate()).isEqualByComparingTo("20.00");

        // 总计：500 营收 / 90 毛利 / 60 净利 / 35 人月
        assertThat(vo.getTotalYtd().getRevenue()).isEqualByComparingTo("500");
        assertThat(vo.getTotalYtd().getGrossProfit()).isEqualByComparingTo("90");
        assertThat(vo.getTotalYtd().getNetProfit()).isEqualByComparingTo("60");
        assertThat(vo.getTotalYtd().getTotalHours()).isEqualByComparingTo("35");
        assertThat(vo.getTotalYtd().getGrossProfitRate()).isEqualByComparingTo("18.00");
    }

    @Test
    @DisplayName("YTD 聚合：营收为 0 时比率置空不报错")
    void queryYearZeroRevenueRateNull() {
        when(reportMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                row("2026-01", "会员通", null, "-100", "-100", "1")
        ));

        BizLineProfitReportVO vo = service.queryYear(2026);

        assertThat(vo.getTotalYtd().getGrossProfitRate()).isNull();
        assertThat(vo.getTotalYtd().getGrossProfit()).isEqualByComparingTo("-100");
    }
}
