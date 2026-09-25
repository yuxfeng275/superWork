package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.ProjectProfitAllocationBatchRequest;
import com.bu.management.entity.BizLineProfitReport;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.ProjectProfitAllocation;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.mapper.BizLineProfitReportMapper;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.ProjectProfitAllocationMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueMonthCloseMapper;
import com.bu.management.vo.ProjectProfitMonthSyncVO;
import com.bu.management.vo.ProjectProfitReportVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目利润表组装口径：镜像驱动月份轴、full/aggregate/simple 行模式、含税→未税换算、
 * 差额行、H1 聚合、过滤、分配 upsert 校验、月度一键同步编排。
 */
@ExtendWith(MockitoExtension.class)
class ProjectProfitServiceTest {

    @Mock
    private BizLineProfitReportMapper reportMapper;
    @Mock
    private BusinessLineMapper businessLineMapper;
    @Mock
    private RevenueCostEntryMapper costEntryMapper;
    @Mock
    private RevenueContractEntryMapper contractEntryMapper;
    @Mock
    private ProjectProfitAllocationMapper allocationMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private WorktimeMonthlySyncService worktimeMonthlySyncService;
    @Mock
    private BusinessLineProfitService businessLineProfitService;
    @Mock
    private RevenueMonthCloseMapper monthCloseMapper;

    private ProjectProfitService service;

    private BusinessLine saas;      // full，税率 6%
    private BusinessLine member;    // aggregate，税率 6%
    private BusinessLine precise;   // simple，税率 6%
    private Project royal;          // id=100 根
    private Project royalPms;       // id=101 子（父=100）
    private Project aoyou;          // id=200 根
    private Project kabrita;        // id=201 根，别名源（佳贝艾特→澳优）

    @BeforeEach
    void setUp() {
        service = new ProjectProfitService(reportMapper, businessLineMapper, costEntryMapper,
                contractEntryMapper, allocationMapper, projectMapper,
                worktimeMonthlySyncService, businessLineProfitService, monthCloseMapper);
        saas = line(1L, "全域-全渠道-全域云鹿Saas", "full", "6");
        member = line(2L, "全域-全渠道-会员通", "aggregate", "6");
        precise = line(3L, "全域-全渠道-全域私域精准", "simple", "6");
        royal = project(100L, 1L, null, "皇家");
        royalPms = project(101L, 1L, 100L, "皇家/PMS");
        aoyou = project(200L, 1L, null, "澳优");
        kabrita = project(201L, 1L, null, "佳贝艾特");
    }

    private BusinessLine line(Long id, String name, String mode, String taxRate) {
        BusinessLine line = new BusinessLine();
        line.setId(id);
        line.setName(name);
        line.setStatus(1);
        line.setRevenueMode(mode);
        line.setKpiReportGroup("分组" + id);
        line.setTaxRate(new BigDecimal(taxRate));
        return line;
    }

    private Project project(Long id, Long lineId, Long parentId, String name) {
        Project project = new Project();
        project.setId(id);
        project.setBusinessLineId(lineId);
        project.setParentId(parentId);
        project.setName(name);
        return project;
    }

    private BizLineProfitReport mirror(String month, Long lineId, String revenue, String hours, String laborCost) {
        BizLineProfitReport row = new BizLineProfitReport();
        row.setYearMonth(month);
        row.setBusinessLineId(lineId);
        row.setRevenue(revenue == null ? null : new BigDecimal(revenue));
        row.setTotalHours(hours == null ? null : new BigDecimal(hours));
        row.setLaborCost1(laborCost == null ? null : new BigDecimal(laborCost));
        return row;
    }

    private RevenueCostEntry cost(String month, Long lineId, String workType, Long projectId,
                                  String hours, String costAmount) {
        RevenueCostEntry entry = new RevenueCostEntry();
        entry.setYearMonth(month);
        entry.setBusinessLineId(lineId);
        entry.setWorkType(workType);
        entry.setProjectId(projectId);
        entry.setHours(new BigDecimal(hours));
        entry.setCostAmount(new BigDecimal(costAmount));
        entry.setPending(0);
        return entry;
    }

    private RevenueContractEntry contract(Long lineId, Long projectId, String receivable, String deliveryDate) {
        RevenueContractEntry entry = new RevenueContractEntry();
        entry.setBizLineId(lineId);
        entry.setProjectId(projectId);
        entry.setReceivableAmount(new BigDecimal(receivable));
        entry.setDeliveryDate(LocalDate.parse(deliveryDate));
        entry.setPending(0);
        return entry;
    }

    private ProjectProfitAllocation allocation(String month, Long lineId, Long projectId,
                                               String costType, String amount) {
        ProjectProfitAllocation allocation = new ProjectProfitAllocation();
        allocation.setYearMonth(month);
        allocation.setBusinessLineId(lineId);
        allocation.setProjectId(projectId);
        allocation.setCostType(costType);
        allocation.setAmount(new BigDecimal(amount));
        return allocation;
    }

    /** 主数据集：2026-01/02 两月，三条业务线 */
    private void stubFullDataset() {
        stubFullDataset(List.of(
                cost("2026-01", 1L, "project", 101L, "2", "300"),   // 子项目 → 皇家
                cost("2026-01", 1L, "project", 201L, "1", "100"),   // 佳贝艾特 → 澳优
                cost("2026-01", 1L, "sales", null, "0.5", "50"),
                cost("2026-01", 1L, "project", null, "0.3", "30"),  // 业务线级 → 销售-业务线
                cost("2026-02", 1L, "project", 100L, "1", "100"),
                cost("2026-01", 2L, "project", null, "5", "290"),
                cost("2026-01", 2L, "sales", null, "0.2", "10"),
                cost("2026-01", 3L, "project", 301L, "1.5", "80"),
                cost("2026-01", 3L, "sales", null, "0.5", "20")));
    }

    private void stubFullDataset(List<RevenueCostEntry> costRows) {
        when(businessLineMapper.selectList(any())).thenReturn(List.of(saas, member, precise));
        BizLineProfitReport saasJan = mirror("2026-01", 1L, "1060", "10", "800");
        saasJan.setSmsCost(new BigDecimal("10"));
        saasJan.setDirectCost(new BigDecimal("20"));
        saasJan.setPlatformFee(new BigDecimal("5"));
        saasJan.setOutsourcing(new BigDecimal("30"));
        when(reportMapper.selectList(any())).thenReturn(List.of(
                saasJan,
                mirror("2026-01", 2L, "2000", "5", "300"),
                mirror("2026-01", 3L, "500", "2", "100"),
                mirror("2026-02", 1L, "1060", "4", "200")));
        when(costEntryMapper.selectList(any())).thenReturn(costRows);
        when(allocationMapper.selectList(any())).thenReturn(List.of(
                allocation("2026-01", 1L, 100L, "direct", "20"),
                allocation("2026-01", 1L, 100L, "outsourcing", "30")));
        when(projectMapper.selectList(any())).thenReturn(List.of(royal, royalPms, aoyou, kabrita));
        when(contractEntryMapper.selectList(any())).thenReturn(List.of(
                contract(1L, 101L, "636", "2026-01-15"),   // ÷1.06 → 600
                contract(1L, 201L, "424", "2026-01-20"),   // ÷1.06 → 400（佳贝艾特归澳优）
                contract(1L, 100L, "212", "2026-02-10"))); // ÷1.06 → 200
    }

    private ProjectProfitReportVO.Line lineOf(ProjectProfitReportVO.Block block, Long lineId) {
        return block.getLines().stream()
                .filter(l -> l.getBusinessLineId().equals(lineId)).findFirst().orElseThrow();
    }

    private ProjectProfitReportVO.Row rowOf(ProjectProfitReportVO.Line line, String rowType, String projectName) {
        return line.getRows().stream()
                .filter(r -> r.getRowType().equals(rowType)
                        && (projectName == null || projectName.equals(r.getProjectName())))
                .findFirst().orElseThrow();
    }

    // ==================== 月份轴 ====================

    @Test
    @DisplayName("月份轴只来自镜像：成本明细有数据但镜像缺失的月份不出块")
    void monthAxisDrivenByMirror() {
        // 含一条镜像未覆盖月份（2026-03）的成本行
        stubFullDataset(List.of(
                cost("2026-01", 1L, "project", 101L, "2", "300"),
                cost("2026-01", 1L, "project", 201L, "1", "100"),
                cost("2026-01", 1L, "sales", null, "0.5", "50"),
                cost("2026-01", 1L, "project", null, "0.3", "30"),
                cost("2026-02", 1L, "project", 100L, "1", "100"),
                cost("2026-03", 1L, "project", 100L, "9", "999"),   // 镜像无 2026-03
                cost("2026-01", 2L, "project", null, "5", "290"),
                cost("2026-01", 2L, "sales", null, "0.2", "10"),
                cost("2026-01", 3L, "project", 301L, "1.5", "80"),
                cost("2026-01", 3L, "sales", null, "0.5", "20")));

        ProjectProfitReportVO vo = service.query(2026, null, null, null, null, null);

        assertThat(vo.getAvailableMonths()).containsExactly("2026-01", "2026-02");
        assertThat(vo.getBlocks()).extracting(ProjectProfitReportVO.Block::getKey)
                .containsExactly("2026-01", "2026-02");
    }

    // ==================== full 线组装 ====================

    @Test
    @DisplayName("full 线：子项目工时归并根项目、别名（佳贝艾特→澳优）合并、合同含税÷1.06、销售/业务线行拆分")
    void fullLineAssembly() {
        stubFullDataset();

        ProjectProfitReportVO vo = service.query(2026, List.of(1), null, null, null, null);

        assertThat(vo.getBlocks()).hasSize(1);
        ProjectProfitReportVO.Line line = lineOf(vo.getBlocks().get(0), 1L);

        ProjectProfitReportVO.Row royalRow = rowOf(line, "PROJECT", "皇家");
        assertThat(royalRow.getHours()).isEqualByComparingTo("2");      // 子项目 101 归并
        assertThat(royalRow.getCost()).isEqualByComparingTo("300");
        assertThat(royalRow.getRevenue()).isEqualByComparingTo("600.00"); // 636 含税 ÷1.06
        assertThat(royalRow.getDirectCost()).isEqualByComparingTo("20");  // 手动分配
        assertThat(royalRow.getOutsourcing()).isEqualByComparingTo("30");
        assertThat(royalRow.getEditable()).isTrue();
        assertThat(royalRow.getGrossProfit()).isEqualByComparingTo("250"); // 600−20−30−300
        assertThat(royalRow.getGrossProfitRate()).isEqualByComparingTo("41.67");

        ProjectProfitReportVO.Row aoyouRow = rowOf(line, "PROJECT", "澳优");
        assertThat(aoyouRow.getHours()).isEqualByComparingTo("1");       // 佳贝艾特(201) 归并
        assertThat(aoyouRow.getCost()).isEqualByComparingTo("100");
        assertThat(aoyouRow.getRevenue()).isEqualByComparingTo("400.00");
        // 佳贝艾特不单独成行
        assertThat(line.getRows().stream()
                .filter(r -> "PROJECT".equals(r.getRowType()))
                .map(ProjectProfitReportVO.Row::getProjectName))
                .containsExactly("皇家", "澳优");

        ProjectProfitReportVO.Row sales = rowOf(line, "SALES", null);
        assertThat(sales.getCategory()).isEqualTo("销售");
        assertThat(sales.getProjectName()).isEqualTo("销售");
        assertThat(sales.getHours()).isEqualByComparingTo("0.5");
        assertThat(sales.getCost()).isEqualByComparingTo("50");
        assertThat(sales.getRevenue()).isEqualByComparingTo("0");
        assertThat(sales.getGrossProfitRate()).isNull();                 // 营收 0 → 率 null

        ProjectProfitReportVO.Row lineOther = rowOf(line, "LINE_OTHER", null);
        assertThat(lineOther.getProjectName()).isEqualTo("业务线");
        assertThat(lineOther.getHours()).isEqualByComparingTo("0.3");    // project_id 为空的 project 行
        assertThat(lineOther.getCost()).isEqualByComparingTo("30");

        // 合计行 = 镜像值
        ProjectProfitReportVO.Row total = line.getTotal();
        assertThat(total.getRevenue()).isEqualByComparingTo("1060");
        assertThat(total.getSmsCost()).isEqualByComparingTo("10");
        assertThat(total.getDirectCost()).isEqualByComparingTo("20");
        assertThat(total.getPlatformFee()).isEqualByComparingTo("5");
        assertThat(total.getOutsourcing()).isEqualByComparingTo("30");
        assertThat(total.getHours()).isEqualByComparingTo("10");
        assertThat(total.getCost()).isEqualByComparingTo("800");
        assertThat(total.getGrossProfit()).isEqualByComparingTo("195");  // 1060−10−20−5−30−800
        assertThat(total.getGrossProfitRate()).isEqualByComparingTo("18.40");

        // 差额行 = 合计 − Σ明细
        ProjectProfitReportVO.Row residual = line.getResidual();
        assertThat(residual.getRevenue()).isEqualByComparingTo("60");    // 1060−600−400
        assertThat(residual.getSmsCost()).isEqualByComparingTo("10");    // 未分配
        assertThat(residual.getDirectCost()).isEqualByComparingTo("0");  // 20 已分配
        assertThat(residual.getPlatformFee()).isEqualByComparingTo("5");
        assertThat(residual.getOutsourcing()).isEqualByComparingTo("0");
        assertThat(residual.getHours()).isEqualByComparingTo("6.2");     // 10−2−1−0.5−0.3
        assertThat(residual.getCost()).isEqualByComparingTo("320");      // 800−300−100−50−30
    }

    @Test
    @DisplayName("aggregate 线：项目集行继承镜像营收与 6 成本列，销售行单列，无业务线行")
    void aggregateLineAssembly() {
        stubFullDataset();

        ProjectProfitReportVO.Line line = lineOf(
                service.query(2026, List.of(1), null, null, null, null).getBlocks().get(0), 2L);

        ProjectProfitReportVO.Row agg = rowOf(line, "PROJECT", "项目集");
        assertThat(agg.getProjectId()).isNull();
        assertThat(agg.getEditable()).isFalse();
        assertThat(agg.getRevenue()).isEqualByComparingTo("2000");       // 镜像线级值
        assertThat(agg.getHours()).isEqualByComparingTo("5");            // 含 project_id 空
        assertThat(agg.getCost()).isEqualByComparingTo("290");
        assertThat(agg.getGrossProfit()).isEqualByComparingTo("1710");

        ProjectProfitReportVO.Row sales = rowOf(line, "SALES", null);
        assertThat(sales.getHours()).isEqualByComparingTo("0.2");
        assertThat(sales.getCost()).isEqualByComparingTo("10");

        assertThat(line.getRows().stream().filter(r -> "LINE_OTHER".equals(r.getRowType()))).isEmpty();
        assertThat(line.getTotal().getGrossProfit()).isEqualByComparingTo("1700"); // 2000−300
        assertThat(line.getResidual().getRevenue()).isEqualByComparingTo("0");
        assertThat(line.getResidual().getCost()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("simple 线：单行合并 project+sales 工时成本，营收取镜像")
    void simpleLineAssembly() {
        stubFullDataset();

        ProjectProfitReportVO.Line line = lineOf(
                service.query(2026, List.of(1), null, null, null, null).getBlocks().get(0), 3L);

        assertThat(line.getRows()).hasSize(1);
        ProjectProfitReportVO.Row row = line.getRows().get(0);
        assertThat(row.getProjectName()).isEqualTo("全域-全渠道-全域私域精准");
        assertThat(row.getRevenue()).isEqualByComparingTo("500");
        assertThat(row.getHours()).isEqualByComparingTo("2");            // 1.5+0.5
        assertThat(row.getCost()).isEqualByComparingTo("100");           // 80+20
        assertThat(row.getGrossProfit()).isEqualByComparingTo("400");
        assertThat(line.getResidual().getRevenue()).isEqualByComparingTo("0");
        assertThat(line.getResidual().getHours()).isEqualByComparingTo("0");
        assertThat(line.getResidual().getCost()).isEqualByComparingTo("0");
    }

    // ==================== 差额行 / 公式 ====================

    @Test
    @DisplayName("差额行 = 合计 − Σ明细：镜像 1000、项目行 600、销售行 0 → 差额 400")
    void residualRow() {
        BusinessLine line = line(9L, "测试Saas", "full", "0");
        when(businessLineMapper.selectList(any())).thenReturn(List.of(line));
        when(reportMapper.selectList(any())).thenReturn(List.of(mirror("2026-01", 9L, "1000", "5", "800")));
        when(costEntryMapper.selectList(any())).thenReturn(List.of(
                cost("2026-01", 9L, "project", 900L, "5", "800")));
        when(allocationMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(any())).thenReturn(List.of(project(900L, 9L, null, "项目A")));
        when(contractEntryMapper.selectList(any())).thenReturn(List.of(
                contract(9L, 900L, "600", "2026-01-15"))); // 税率 0 → 未税 600

        ProjectProfitReportVO.Line out = lineOf(
                service.query(2026, null, null, null, null, null).getBlocks().get(0), 9L);

        assertThat(out.getResidual().getRevenue()).isEqualByComparingTo("400");
        assertThat(out.getResidual().getCost()).isEqualByComparingTo("0");
        assertThat(out.getResidual().getHours()).isEqualByComparingTo("0");
        // 行公式：grossProfit = revenue − 各成本列 − cost
        assertThat(out.getRows().get(0).getGrossProfit()).isEqualByComparingTo("-200"); // 600−800
    }

    // ==================== H1 聚合 ====================

    @Test
    @DisplayName("H1 聚合：跨月求和且率重算；H2 无数据块省略")
    void h1Aggregation() {
        stubFullDataset();

        ProjectProfitReportVO vo = service.query(2026, null, List.of("H1"), null, null, null);

        assertThat(vo.getBlocks()).hasSize(1);
        ProjectProfitReportVO.Block block = vo.getBlocks().get(0);
        assertThat(block.getKey()).isEqualTo("H1");
        assertThat(block.getLabel()).isEqualTo("H1");
        ProjectProfitReportVO.Line line = lineOf(block, 1L);
        ProjectProfitReportVO.Row royalRow = rowOf(line, "PROJECT", "皇家");
        assertThat(royalRow.getHours()).isEqualByComparingTo("3");       // 2+1
        assertThat(royalRow.getCost()).isEqualByComparingTo("400");      // 300+100
        assertThat(royalRow.getRevenue()).isEqualByComparingTo("800.00"); // 600+200
        assertThat(royalRow.getGrossProfit()).isEqualByComparingTo("350"); // 800−20−30−400
        assertThat(royalRow.getGrossProfitRate()).isEqualByComparingTo("43.75"); // 率重算
        // 合计 = 镜像跨月求和
        assertThat(line.getTotal().getRevenue()).isEqualByComparingTo("2120");
        assertThat(line.getTotal().getCost()).isEqualByComparingTo("1000");
        assertThat(line.getTotal().getGrossProfit()).isEqualByComparingTo("1055");
        assertThat(line.getTotal().getGrossProfitRate()).isEqualByComparingTo("49.76");

        // H2 与 availableMonths 无交集 → 块省略
        ProjectProfitReportVO h2 = service.query(2026, null, List.of("H2"), null, null, null);
        assertThat(h2.getBlocks()).isEmpty();
    }

    // ==================== 过滤 ====================

    @Test
    @DisplayName("projectIds 仅过滤 PROJECT 行；销售/合计保留，差额按显示行重算")
    void projectIdsFilter() {
        stubFullDataset();

        ProjectProfitReportVO vo = service.query(2026, List.of(1), null, null, null, List.of(100L));

        ProjectProfitReportVO.Line line = lineOf(vo.getBlocks().get(0), 1L);
        assertThat(line.getRows().stream().filter(r -> "PROJECT".equals(r.getRowType())))
                .hasSize(1)
                .allMatch(r -> r.getProjectId().equals(100L));
        // 销售行保留
        assertThat(line.getRows().stream().filter(r -> "SALES".equals(r.getRowType()))).hasSize(1);
        // 差额按显示行重算：1060−600=460（澳优 400 被过滤后进差额）
        assertThat(line.getResidual().getRevenue()).isEqualByComparingTo("460");
        // 其他业务线不受 projectIds 影响
        assertThat(lineOf(vo.getBlocks().get(0), 2L).getRows()).isNotEmpty();
    }

    @Test
    @DisplayName("categories=项目：仅保留项目行，销售行进入差额")
    void categoriesFilter() {
        stubFullDataset();

        ProjectProfitReportVO vo = service.query(2026, List.of(1), null, null, List.of("项目"), null);

        ProjectProfitReportVO.Line line = lineOf(vo.getBlocks().get(0), 1L);
        assertThat(line.getRows()).allMatch(r -> "PROJECT".equals(r.getRowType()));
        // 销售行 50 + 业务线行 30 的成本进入差额：800−300−100=400
        assertThat(line.getResidual().getCost()).isEqualByComparingTo("400");
    }

    @Test
    @DisplayName("lineOptions 在全部 managed 线上构建：full 线含根项目（别名源除外），其余为空")
    void lineOptionsUnfiltered() {
        stubFullDataset();

        ProjectProfitReportVO vo = service.query(2026, List.of(1), null, List.of(1L), null, null);

        assertThat(vo.getLineOptions()).hasSize(3);
        ProjectProfitReportVO.LineOption saasOption = vo.getLineOptions().get(0);
        assertThat(saasOption.getBusinessLineId()).isEqualTo(1L);
        assertThat(saasOption.getProjects())
                .extracting(ProjectProfitReportVO.ProjectOption::getProjectName)
                .containsExactly("皇家", "澳优");
        assertThat(vo.getLineOptions().get(1).getProjects()).isEmpty();
    }

    // ==================== 分配 upsert 校验 ====================

    private ProjectProfitAllocationBatchRequest batchRequest(Long lineId, Long projectId,
                                                             String costType, String amount) {
        ProjectProfitAllocationBatchRequest request = new ProjectProfitAllocationBatchRequest();
        request.setYearMonth("2026-01");
        request.setBusinessLineId(lineId);
        request.setProjectId(projectId);
        ProjectProfitAllocationBatchRequest.Item item = new ProjectProfitAllocationBatchRequest.Item();
        item.setCostType(costType);
        item.setAmount(new BigDecimal(amount));
        request.setItems(List.of(item));
        return request;
    }

    @Test
    @DisplayName("分配校验：非 full 业务线拒绝")
    void allocationRejectsNonFullLine() {
        when(businessLineMapper.selectById(2L)).thenReturn(member);

        assertThatThrownBy(() -> service.saveAllocations(batchRequest(2L, 100L, "direct", "100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("full");
    }

    @Test
    @DisplayName("分配校验：项目不属于该业务线拒绝")
    void allocationRejectsCrossLineProject() {
        when(businessLineMapper.selectById(1L)).thenReturn(saas);
        when(projectMapper.selectById(900L)).thenReturn(project(900L, 2L, null, "别人项目"));

        assertThatThrownBy(() -> service.saveAllocations(batchRequest(1L, 900L, "direct", "100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于");
    }

    @Test
    @DisplayName("分配校验：非法 costType 拒绝")
    void allocationRejectsBadCostType() {
        when(businessLineMapper.selectById(1L)).thenReturn(saas);
        when(projectMapper.selectById(100L)).thenReturn(royal);

        assertThatThrownBy(() -> service.saveAllocations(batchRequest(1L, 100L, "bad_type", "100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("成本类型");
    }

    @Test
    @DisplayName("分配 upsert：唯一键存在则更新，不存在则插入")
    void allocationUpsert() {
        when(businessLineMapper.selectById(1L)).thenReturn(saas);
        when(projectMapper.selectById(100L)).thenReturn(royal);
        ProjectProfitAllocation existing = allocation("2026-01", 1L, 100L, "direct", "1");
        existing.setId(5L);
        when(allocationMapper.selectOne(any())).thenReturn(existing, (ProjectProfitAllocation) null);

        ProjectProfitAllocationBatchRequest request = batchRequest(1L, 100L, "direct", "100");
        ProjectProfitAllocationBatchRequest.Item sms = new ProjectProfitAllocationBatchRequest.Item();
        sms.setCostType("sms");
        sms.setAmount(new BigDecimal("50"));
        request.setItems(List.of(request.getItems().get(0), sms));

        service.saveAllocations(request);

        ArgumentCaptor<ProjectProfitAllocation> updateCaptor = ArgumentCaptor.forClass(ProjectProfitAllocation.class);
        verify(allocationMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getId()).isEqualTo(5L);
        assertThat(updateCaptor.getValue().getAmount()).isEqualByComparingTo("100");
        ArgumentCaptor<ProjectProfitAllocation> insertCaptor = ArgumentCaptor.forClass(ProjectProfitAllocation.class);
        verify(allocationMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getCostType()).isEqualTo("sms");
        assertThat(insertCaptor.getValue().getAmount()).isEqualByComparingTo("50");
    }

    // ==================== 月度一键同步编排 ====================

    private WorktimeSyncLog syncLog(String syncType) {
        WorktimeSyncLog log = new WorktimeSyncLog();
        log.setSyncType(syncType);
        log.setStatus("success");
        return log;
    }

    @Test
    @DisplayName("syncMonth 编排：先工时+成本重拉，后镜像刷新；logs 3 条、yearMonth 透传")
    void syncMonthOrchestrationOrder() {
        when(monthCloseMapper.selectCount(any())).thenReturn(0L);
        when(worktimeMonthlySyncService.syncConfirmedMonths("2026-08", "manual"))
                .thenReturn(List.of(syncLog("worklog"), syncLog("cost")));
        when(businessLineProfitService.syncMonth("2026-08", "manual")).thenReturn(syncLog("bl_profit"));
        when(businessLineMapper.selectList(any())).thenReturn(List.of());

        ProjectProfitMonthSyncVO vo = service.syncMonth("2026-08");

        InOrder order = inOrder(worktimeMonthlySyncService, businessLineProfitService);
        order.verify(worktimeMonthlySyncService).syncConfirmedMonths("2026-08", "manual");
        order.verify(businessLineProfitService).syncMonth("2026-08", "manual");
        assertThat(vo.getYearMonth()).isEqualTo("2026-08");
        assertThat(vo.getMonthClosed()).isFalse();
        assertThat(vo.getLogs()).extracting(WorktimeSyncLog::getSyncType)
                .containsExactly("worklog", "cost", "bl_profit");
        assertThat(vo.getLines()).isEmpty();
    }

    @Test
    @DisplayName("syncMonth 已完结月：monthClosed=true，工时/成本被跳过，logs 仅 bl_profit")
    void syncMonthClosed() {
        when(monthCloseMapper.selectCount(any())).thenReturn(1L);
        when(worktimeMonthlySyncService.syncConfirmedMonths("2026-08", "manual")).thenReturn(List.of());
        when(businessLineProfitService.syncMonth("2026-08", "manual")).thenReturn(syncLog("bl_profit"));
        when(businessLineMapper.selectList(any())).thenReturn(List.of());

        ProjectProfitMonthSyncVO vo = service.syncMonth("2026-08");

        assertThat(vo.getMonthClosed()).isTrue();
        assertThat(vo.getLogs()).extracting(WorktimeSyncLog::getSyncType).containsExactly("bl_profit");
    }

    @Test
    @DisplayName("syncMonth 对齐：镜像 1000/成本 800 vs 明细 600/800 → 差额 400/0，aligned=false")
    void syncMonthAlignmentResidual() {
        BusinessLine line = line(9L, "测试Saas", "full", "0");
        when(monthCloseMapper.selectCount(any())).thenReturn(0L);
        when(worktimeMonthlySyncService.syncConfirmedMonths("2026-08", "manual")).thenReturn(List.of());
        when(businessLineProfitService.syncMonth("2026-08", "manual")).thenReturn(syncLog("bl_profit"));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(line));
        when(reportMapper.selectList(any())).thenReturn(List.of(mirror("2026-08", 9L, "1000", "5", "800")));
        when(costEntryMapper.selectList(any())).thenReturn(List.of(
                cost("2026-08", 9L, "project", 900L, "5", "800")));
        when(allocationMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(any())).thenReturn(List.of(project(900L, 9L, null, "项目A")));
        when(contractEntryMapper.selectList(any())).thenReturn(List.of(
                contract(9L, 900L, "600", "2026-08-15")));

        ProjectProfitMonthSyncVO vo = service.syncMonth("2026-08");

        assertThat(vo.getLines()).hasSize(1);
        ProjectProfitMonthSyncVO.LineAlignment alignment = vo.getLines().get(0);
        assertThat(alignment.getBusinessLineId()).isEqualTo(9L);
        assertThat(alignment.getRevenueResidual()).isEqualByComparingTo("400");
        assertThat(alignment.getCostResidual()).isEqualByComparingTo("0");
        assertThat(alignment.getHoursResidual()).isEqualByComparingTo("0");
        assertThat(alignment.getAligned()).isFalse();
    }

    @Test
    @DisplayName("syncMonth 容错：工时/成本重拉抛异常 → 转为 failed 日志，镜像刷新仍执行，不向上抛")
    void syncMonthResilientToWorktimeFailure() {
        when(monthCloseMapper.selectCount(any())).thenReturn(0L);
        when(worktimeMonthlySyncService.syncConfirmedMonths("2026-08", "manual"))
                .thenThrow(new IllegalStateException("工时系统集成未启用或未配置账号"));
        when(businessLineProfitService.syncMonth("2026-08", "manual")).thenReturn(syncLog("bl_profit"));
        when(businessLineMapper.selectList(any())).thenReturn(List.of());

        ProjectProfitMonthSyncVO vo = service.syncMonth("2026-08");

        assertThat(vo.getLogs()).hasSize(2);
        assertThat(vo.getLogs().get(0).getStatus()).isEqualTo("failed");
        assertThat(vo.getLogs().get(0).getMessage()).contains("未启用");
        assertThat(vo.getLogs().get(1).getSyncType()).isEqualTo("bl_profit");
    }

    @Test
    @DisplayName("syncMonth 对齐：明细=合计 → aligned=true")
    void syncMonthAlignmentAligned() {
        BusinessLine line = line(9L, "测试Saas", "full", "0");
        when(monthCloseMapper.selectCount(any())).thenReturn(0L);
        when(worktimeMonthlySyncService.syncConfirmedMonths("2026-08", "manual")).thenReturn(List.of());
        when(businessLineProfitService.syncMonth("2026-08", "manual")).thenReturn(syncLog("bl_profit"));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(line));
        when(reportMapper.selectList(any())).thenReturn(List.of(mirror("2026-08", 9L, "1000", "5", "800")));
        when(costEntryMapper.selectList(any())).thenReturn(List.of(
                cost("2026-08", 9L, "project", 900L, "5", "800")));
        when(allocationMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(any())).thenReturn(List.of(project(900L, 9L, null, "项目A")));
        when(contractEntryMapper.selectList(any())).thenReturn(List.of(
                contract(9L, 900L, "1000", "2026-08-15")));

        ProjectProfitMonthSyncVO vo = service.syncMonth("2026-08");

        assertThat(vo.getLines()).hasSize(1);
        assertThat(vo.getLines().get(0).getAligned()).isTrue();
        assertThat(vo.getLines().get(0).getRevenueResidual()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("syncMonth 镜像缺失月份：lines 为空列表，不抛异常")
    void syncMonthMirrorMissing() {
        BusinessLine line = line(9L, "测试Saas", "full", "0");
        when(monthCloseMapper.selectCount(any())).thenReturn(0L);
        when(worktimeMonthlySyncService.syncConfirmedMonths("2026-08", "manual")).thenReturn(List.of());
        when(businessLineProfitService.syncMonth("2026-08", "manual")).thenReturn(syncLog("bl_profit"));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(line));
        when(reportMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(any())).thenReturn(List.of(project(900L, 9L, null, "项目A")));
        when(contractEntryMapper.selectList(any())).thenReturn(List.of());

        ProjectProfitMonthSyncVO vo = service.syncMonth("2026-08");

        assertThat(vo.getLines()).isEmpty();
    }

    @Test
    @DisplayName("syncMonth 参数校验：非法月份/未来月份抛 IllegalArgumentException")
    void syncMonthValidation() {
        assertThatThrownBy(() -> service.syncMonth("2026-13"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.syncMonth("202601"))
                .isInstanceOf(IllegalArgumentException.class);
        String future = YearMonth.now().plusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        assertThatThrownBy(() -> service.syncMonth(future))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未来");
    }
}
