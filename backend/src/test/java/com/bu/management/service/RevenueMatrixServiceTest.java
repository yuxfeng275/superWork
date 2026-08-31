package com.bu.management.service;

import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueEstimateEntry;
import com.bu.management.entity.RevenueWorklogEntry;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueEstimateEntryMapper;
import com.bu.management.mapper.RevenueSalesProjectMapper;
import com.bu.management.mapper.RevenueWorklogEntryMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.bu.management.vo.RevenueMatrixVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class RevenueMatrixServiceTest {

    @Mock private RevenueWorklogEntryMapper worklogEntryMapper;
    @Mock private RevenueCostEntryMapper costEntryMapper;
    @Mock private RevenueEstimateEntryMapper estimateEntryMapper;
    @Mock private RevenueSalesProjectMapper salesProjectMapper;
    @Mock private RevenueMonthService monthService;
    @Mock private BusinessLineMapper businessLineMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private SalesOpportunityMapper opportunityMapper;

    private RevenueMatrixService service;

    @BeforeEach
    void setUp() {
        service = new RevenueMatrixService(worklogEntryMapper, costEntryMapper, estimateEntryMapper,
                salesProjectMapper, monthService, businessLineMapper, projectMapper, opportunityMapper);

        BusinessLine custom = new BusinessLine();
        custom.setId(1L);
        custom.setName("全渠道云鹿定制");
        lenient().when(businessLineMapper.selectList(any())).thenReturn(List.of(custom));

        Project royal = project(11L, 1L, null, "皇家项目");
        Project pms = project(15L, 1L, 11L, "PMS");
        lenient().when(projectMapper.selectList(null)).thenReturn(List.of(royal, pms));
        lenient().when(salesProjectMapper.selectList(null)).thenReturn(List.of());
        lenient().when(opportunityMapper.selectList(null)).thenReturn(List.of());
        lenient().when(monthService.closedMonths()).thenReturn(Set.of("2026-07"));
    }

    private Project project(Long id, Long lineId, Long parentId, String name) {
        Project project = new Project();
        project.setId(id);
        project.setBusinessLineId(lineId);
        project.setParentId(parentId);
        project.setName(name);
        project.setStatus(1);
        return project;
    }

    private RevenueWorklogEntry worklog(String month, Long lineId, Long projectId, String hours) {
        RevenueWorklogEntry entry = new RevenueWorklogEntry();
        entry.setYearMonth(month);
        entry.setBusinessLineId(lineId);
        entry.setProjectId(projectId);
        entry.setWorkType("project");
        entry.setHours(new BigDecimal(hours));
        entry.setPending(0);
        return entry;
    }

    private RevenueCostEntry cost(String month, Long lineId, Long projectId, String hours, String cost) {
        RevenueCostEntry entry = new RevenueCostEntry();
        entry.setYearMonth(month);
        entry.setBusinessLineId(lineId);
        entry.setProjectId(projectId);
        entry.setWorkType("project");
        entry.setHours(new BigDecimal(hours));
        entry.setCostAmount(new BigDecimal(cost));
        entry.setPending(0);
        return entry;
    }

    @Test
    void closedMonthShowsActualWithWorklogHoursAndCostAmount() {
        lenient().when(worklogEntryMapper.selectList(any())).thenReturn(List.of(
                worklog("2026-07", 1L, 11L, "3.8"),
                worklog("2026-07", 1L, 15L, "0.5")   // 子项目工时并入根项目行
        ));
        lenient().when(costEntryMapper.selectList(any())).thenReturn(List.of(
                cost("2026-07", 1L, 11L, "4.3", "98000")
        ));
        lenient().when(estimateEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueMatrixVO matrix = service.getMatrix(2026);
        RevenueMatrixVO.Row royal = matrix.getLines().get(0).getSections().get(0).getRows().stream()
                .filter(row -> row.getRowKey().equals("p-11")).findFirst().orElseThrow();

        RevenueMatrixVO.Cell july = royal.getMonths().get(6);
        assertThat(july.getSource()).isEqualTo("actual");
        assertThat(july.getHours()).isEqualByComparingTo("4.3");      // 3.8 + 0.5 子项目
        assertThat(july.getCost()).isEqualByComparingTo("98000");
        assertThat(royal.getUnitPrice()).isEqualByComparingTo("22790.70");  // 98000 / 4.3

        RevenueMatrixVO.Cell august = royal.getMonths().get(7);
        assertThat(august.getSource()).isNull();
        assertThat(august.getHours()).isEqualByComparingTo("0");
    }

    @Test
    void unclosedMonthShowsEstimateOnly() {
        lenient().when(worklogEntryMapper.selectList(any())).thenReturn(List.of(
                worklog("2026-08", 1L, 11L, "3.8")   // 未完结月的实际值不展示
        ));
        lenient().when(costEntryMapper.selectList(any())).thenReturn(List.of());
        RevenueEstimateEntry estimate = new RevenueEstimateEntry();
        estimate.setYearMonth("2026-09");
        estimate.setBusinessLineId(1L);
        estimate.setProjectId(11L);
        estimate.setWorkType("project");
        estimate.setPersonMonths(new BigDecimal("1.5"));
        estimate.setUnitPrice(new BigDecimal("20000"));
        estimate.setAmount(new BigDecimal("30000"));
        lenient().when(estimateEntryMapper.selectList(any())).thenReturn(List.of(estimate));

        RevenueMatrixVO matrix = service.getMatrix(2026);
        RevenueMatrixVO.Row royal = matrix.getLines().get(0).getSections().get(0).getRows().stream()
                .filter(row -> row.getRowKey().equals("p-11")).findFirst().orElseThrow();

        assertThat(royal.getMonths().get(7).getSource()).isNull();          // 8月：实际不展示
        RevenueMatrixVO.Cell september = royal.getMonths().get(8);
        assertThat(september.getSource()).isEqualTo("estimate");
        assertThat(september.getHours()).isEqualByComparingTo("1.5");
        assertThat(september.getCost()).isEqualByComparingTo("30000");
        assertThat(september.getEstimateCount()).isEqualTo(1);

        assertThat(matrix.getOverview().getTotalHours()).isEqualByComparingTo("1.5");
        assertThat(matrix.getOverview().getClosedMonthCount()).isEqualTo(1);
    }

    @Test
    void fixedRowsExistPerBusinessLine() {
        lenient().when(worklogEntryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(costEntryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(estimateEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueMatrixVO matrix = service.getMatrix(2026);
        RevenueMatrixVO.LineBlock block = matrix.getLines().get(0);
        List<String> projectRowKeys = block.getSections().get(0).getRows().stream()
                .map(RevenueMatrixVO.Row::getRowKey).toList();
        List<String> salesRowKeys = block.getSections().get(1).getRows().stream()
                .map(RevenueMatrixVO.Row::getRowKey).toList();
        assertThat(projectRowKeys).contains("lp-1", "p-11");
        assertThat(projectRowKeys).doesNotContain("p-15");      // 子项目不单独成行
        assertThat(salesRowKeys).contains("pool-1", "other-1");
    }
}
