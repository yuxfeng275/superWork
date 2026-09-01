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
        custom.setRevenueMode("full");
        BusinessLine member = new BusinessLine();
        member.setId(3L);
        member.setName("会员通");
        member.setRevenueMode("aggregate");
        BusinessLine product = new BusinessLine();
        product.setId(5L);
        product.setName("全渠道产品");
        product.setRevenueMode("simple");
        lenient().when(businessLineMapper.selectList(any())).thenReturn(List.of(custom, member, product));

        Project royal = project(11L, 1L, null, "皇家项目");
        Project pms = project(15L, 1L, 11L, "PMS");
        Project aoyou = project(24L, 1L, null, "澳优");
        Project jiabe = project(8L, 1L, null, "佳贝艾特");
        Project hipro = project(9L, 1L, null, "海普诺凯");
        lenient().when(projectMapper.selectList(null)).thenReturn(List.of(royal, pms, aoyou, jiabe, hipro));
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
    void fullModeHasProjectAndSalesPoolRowsWithoutLinePool() {
        lenient().when(worklogEntryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(costEntryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(estimateEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueMatrixVO.LineBlock block = service.getMatrix(2026).getLines().get(0);
        assertThat(block.getMode()).isEqualTo("full");
        List<String> projectRowKeys = block.getSections().get(0).getRows().stream()
                .map(RevenueMatrixVO.Row::getRowKey).toList();
        List<String> salesRowKeys = block.getSections().get(1).getRows().stream()
                .map(RevenueMatrixVO.Row::getRowKey).toList();
        assertThat(projectRowKeys).containsExactly("p-11", "p-24"); // 无项目集行；佳贝/海普并入澳优不单独成行
        assertThat(salesRowKeys).contains("pool-1", "other-1");
    }

    @Test
    void lineLevelProjectHoursFoldIntoOtherRowInFullMode() {
        // 业务线级【项目】（projectId=null, workType=project）并入「其他」行
        lenient().when(worklogEntryMapper.selectList(any())).thenReturn(List.of(
                worklog("2026-07", 1L, null, "0.3")
        ));
        lenient().when(costEntryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(estimateEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueMatrixVO.LineBlock block = service.getMatrix(2026).getLines().get(0);
        RevenueMatrixVO.Row other = block.getSections().get(1).getRows().stream()
                .filter(row -> row.getRowKey().equals("other-1")).findFirst().orElseThrow();
        assertThat(other.getMonths().get(6).getHours()).isEqualByComparingTo("0.3");
        assertThat(other.getMonths().get(6).getSource()).isEqualTo("actual");
    }

    @Test
    void aggregateModeCollapsesToTwoRows() {
        lenient().when(worklogEntryMapper.selectList(any())).thenReturn(List.of(
                worklog("2026-07", 3L, null, "1.5")              // 会员通业务线级项目工时
        ));
        lenient().when(costEntryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(estimateEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueMatrixVO.LineBlock member = service.getMatrix(2026).getLines().stream()
                .filter(block -> block.getBusinessLineId() == 3L).findFirst().orElseThrow();
        assertThat(member.getMode()).isEqualTo("aggregate");
        assertThat(member.getSections().get(0).getRows())
                .extracting(RevenueMatrixVO.Row::getRowKey).containsExactly("agg-project-3");
        assertThat(member.getSections().get(1).getRows())
                .extracting(RevenueMatrixVO.Row::getRowKey).containsExactly("agg-sales-3");
        assertThat(member.getSections().get(0).getRows().get(0).getMonths().get(6).getHours())
                .isEqualByComparingTo("1.5");
    }

    @Test
    void jiabeAiteAndHiproRollIntoAoyouRow() {
        // 佳贝艾特(8) + 海普诺凯(9) 的工时并入澳优(24)
        lenient().when(worklogEntryMapper.selectList(any())).thenReturn(List.of(
                worklog("2026-07", 1L, 8L, "0.6"),
                worklog("2026-07", 1L, 9L, "0.4"),
                worklog("2026-07", 1L, 24L, "1.383")
        ));
        lenient().when(costEntryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(estimateEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueMatrixVO.LineBlock block = service.getMatrix(2026).getLines().get(0);
        List<RevenueMatrixVO.Row> projectRows = block.getSections().get(0).getRows();
        assertThat(projectRows).extracting(RevenueMatrixVO.Row::getRowKey)
                .doesNotContain("p-8", "p-9");
        RevenueMatrixVO.Row aoyouRow = projectRows.stream()
                .filter(row -> row.getRowKey().equals("p-24")).findFirst().orElseThrow();
        assertThat(aoyouRow.getMonths().get(6).getHours()).isEqualByComparingTo("2.383");
    }

    @Test
    void simpleLineKeepsHoursButHidesCost() {
        // simple 业务线（全渠道产品）成本计入公司公共投入：矩阵只显示工时
        lenient().when(worklogEntryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(costEntryMapper.selectList(any())).thenReturn(List.of(
                cost("2026-07", 5L, null, "1.0", "15455")
        ));
        lenient().when(estimateEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueMatrixVO.LineBlock product = service.getMatrix(2026).getLines().stream()
                .filter(block -> block.getBusinessLineId() == 5L).findFirst().orElseThrow();
        RevenueMatrixVO.Row row = product.getSections().get(0).getRows().get(0);
        RevenueMatrixVO.Cell july = row.getMonths().get(6);
        assertThat(july.getHours()).isEqualByComparingTo("1.0");
        assertThat(july.getCost()).isEqualByComparingTo("0");
        assertThat(row.getUnitPrice()).isNull();
        assertThat(product.getTotals().getCost()).isEqualByComparingTo("0");
    }

    @Test
    void simpleModeCollapsesToSingleRow() {
        lenient().when(worklogEntryMapper.selectList(any())).thenReturn(List.of(
                worklog("2026-07", 5L, null, "0.2")
        ));
        lenient().when(costEntryMapper.selectList(any())).thenReturn(List.of());
        lenient().when(estimateEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueMatrixVO.LineBlock product = service.getMatrix(2026).getLines().stream()
                .filter(block -> block.getBusinessLineId() == 5L).findFirst().orElseThrow();
        assertThat(product.getMode()).isEqualTo("simple");
        List<RevenueMatrixVO.Row> allRows = product.getSections().stream()
                .flatMap(section -> section.getRows().stream()).toList();
        assertThat(allRows).extracting(RevenueMatrixVO.Row::getRowKey).containsExactly("simple-5");
        assertThat(allRows.get(0).getMonths().get(6).getHours()).isEqualByComparingTo("0.2");
    }
}
