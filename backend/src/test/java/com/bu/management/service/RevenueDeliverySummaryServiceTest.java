package com.bu.management.service;

import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueDeliveryPlan;
import com.bu.management.entity.RevenueOtherCost;
import com.bu.management.entity.RevenueSalesProject;
import com.bu.management.entity.RevenueWorklogEntry;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueDeliveryPlanMapper;
import com.bu.management.mapper.RevenueOtherCostMapper;
import com.bu.management.mapper.RevenueSalesProjectMapper;
import com.bu.management.mapper.RevenueWorklogEntryMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.bu.management.vo.RevenueDeliverySummaryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueDeliverySummaryServiceTest {

    @Mock private RevenueWorklogEntryMapper worklogEntryMapper;
    @Mock private RevenueCostEntryMapper costEntryMapper;
    @Mock private RevenueContractEntryMapper contractEntryMapper;
    @Mock private RevenueDeliveryPlanMapper planMapper;
    @Mock private RevenueOtherCostMapper otherCostMapper;
    @Mock private RevenueMonthService monthService;
    @Mock private BusinessLineMapper businessLineMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private RevenueSalesProjectMapper salesProjectMapper;
    @Mock private SalesOpportunityMapper opportunityMapper;

    private RevenueDeliverySummaryService service;
    private final LocalDate today = LocalDate.of(2026, 9, 2);

    @BeforeEach
    void setUp() {
        service = new RevenueDeliverySummaryService(worklogEntryMapper, costEntryMapper, contractEntryMapper,
                planMapper, otherCostMapper, monthService, businessLineMapper, projectMapper,
                salesProjectMapper, opportunityMapper);
        lenient().when(salesProjectMapper.selectList(null)).thenReturn(List.of());
        lenient().when(opportunityMapper.selectList(null)).thenReturn(List.of());
        lenient().when(monthService.closedMonths())
                .thenReturn(Set.of("2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06", "2026-07"));
        lenient().when(businessLineMapper.selectList(any())).thenReturn(List.of(
                line(1L, "全渠道云鹿定制", "full", 1),
                line(3L, "会员通", "aggregate", 1),
                line(5L, "全渠道产品", "simple", 1),
                line(6L, "海外业务线", "aggregate", 1),
                line(7L, "全域精准", "simple", 1)));
        lenient().when(projectMapper.selectList(null)).thenReturn(List.of(
                project(1L, 1L, null, "皇家项目"),
                project(7L, 1L, null, "飞鹤"),
                project(8L, 1L, null, "佳贝艾特"),
                project(9L, 1L, null, "海普诺凯"),
                project(10L, 1L, null, "Speedo"),
                project(24L, 1L, null, "澳优")));
        // 数据集：2026 年完结月 1-7
        lenient().when(worklogEntryMapper.selectList(any())).thenReturn(List.of(
                worklog("2026-03", 1L, 1L, "10", "project")));
        List<RevenueCostEntry> costs = new ArrayList<>();
        costs.add(cost("2026-03", 1L, 1L, "10", "50000", "project"));
        costs.add(cost("2026-03", 1L, 8L, "2", "12000", "project"));    // 佳贝艾特 → 归并澳优
        costs.add(cost("2026-02", 1L, null, "1", "8000", "sales"));
        costs.add(cost("2026-03", 3L, null, "4", "24000", "project"));
        costs.add(cost("2026-03", 3L, null, "2", "9000", "sales"));
        costs.add(cost("2026-03", 5L, null, "6", "30000", "project"));
        costs.add(cost("2026-03", 5L, null, "2", "9000", "sales"));
        costs.add(cost("2026-08", 5L, null, "5", "2000", "project"));    // 未完结月 → 不计
        costs.add(cost("2026-04", 6L, null, "3", "11000", "project"));
        costs.add(cost("2026-04", 6L, null, "1", "7000", "sales"));
        lenient().when(costEntryMapper.selectList(any())).thenReturn(costs);

        List<RevenueContractEntry> contracts = new ArrayList<>();
        contracts.add(contract("dA", 1L, 1L, "30000", "2026-03", "2026-03-15"));
        contracts.add(contract("dB", 1L, 1L, "70000", "2026-08", "2026-08-20"));
        contracts.add(contract("dC", 1L, 1L, "100000", "2026-07", null));
        contracts.add(contract("dD", 1L, 1L, "50000", "2026-10", "2026-10-15"));   // 未来交付 → 不计已交付
        contracts.add(contract("dI", 1L, 7L, "12345", "2026-06", "2025-12-31"));   // 跨年交付：只进合同总额
        contracts.add(contract("dF", 3L, null, "200000", "2026-05", "2026-05-10"));
        contracts.add(contract("dG", 3L, null, "400000", "2026-09", "2026-09-20"));
        contracts.add(contract("dH", 5L, null, "30000", "2026-03", "2026-03-31"));
        contracts.add(contract("dJ", 6L, null, "40000", "2026-04", "2026-04-30"));
        lenient().when(contractEntryMapper.selectList(any())).thenReturn(contracts);

        List<RevenueDeliveryPlan> plans = new ArrayList<>();
        plans.add(plan("2026-03", 1L, 1L, "40000", "10000"));
        plans.add(plan("2026-10", 1L, 1L, "80000", "20000"));
        plans.add(plan("2026-08", 3L, null, "100000", "30000"));
        lenient().when(planMapper.selectList(any())).thenReturn(plans);

        List<RevenueOtherCost> otherCosts = new ArrayList<>();
        otherCosts.add(other("2026-08", 1L, 1L, "server", "3000"));
        otherCosts.add(other("2026-12", 1L, 1L, "server", "9999"));   // 未来月不计
        otherCosts.add(other("2026-04", 3L, null, "partner", "5000"));
        lenient().when(otherCostMapper.selectList(any())).thenReturn(otherCosts);
    }

    private BusinessLine line(Long id, String name, String mode, Integer costVisible) {
        BusinessLine line = new BusinessLine();
        line.setId(id);
        line.setName(name);
        line.setRevenueMode(mode);
        line.setCostVisible(costVisible);
        line.setStatus(1);
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

    private RevenueWorklogEntry worklog(String month, Long lineId, Long projectId, String hours, String type) {
        RevenueWorklogEntry entry = new RevenueWorklogEntry();
        entry.setYearMonth(month);
        entry.setBusinessLineId(lineId);
        entry.setProjectId(projectId);
        entry.setWorkType(type);
        entry.setHours(new BigDecimal(hours));
        entry.setPending(0);
        return entry;
    }

    private RevenueCostEntry cost(String month, Long lineId, Long projectId, String hours, String amount, String type) {
        RevenueCostEntry entry = new RevenueCostEntry();
        entry.setYearMonth(month);
        entry.setBusinessLineId(lineId);
        entry.setProjectId(projectId);
        entry.setWorkType(type);
        entry.setHours(new BigDecimal(hours));
        entry.setCostAmount(new BigDecimal(amount));
        entry.setPending(0);
        return entry;
    }

    private RevenueContractEntry contract(String detailNo, Long lineId, Long projectId,
                                          String amount, String saleMonth, String delivery) {
        RevenueContractEntry entry = new RevenueContractEntry();
        entry.setDetailNo(detailNo);
        entry.setBizLineId(lineId);
        entry.setProjectId(projectId);
        entry.setReceivableAmount(new BigDecimal(amount));
        entry.setSaleMonth(saleMonth);
        entry.setDeliveryDate(delivery == null ? null : LocalDate.parse(delivery));
        entry.setPending(0);
        return entry;
    }

    private RevenueDeliveryPlan plan(String month, Long lineId, Long projectId, String amount, String labor) {
        RevenueDeliveryPlan plan = new RevenueDeliveryPlan();
        plan.setYearMonth(month);
        plan.setBusinessLineId(lineId);
        plan.setProjectId(projectId);
        plan.setAmountYuan(new BigDecimal(amount));
        plan.setLaborCostYuan(new BigDecimal(labor));
        return plan;
    }

    private RevenueOtherCost other(String month, Long lineId, Long projectId, String type, String amount) {
        RevenueOtherCost cost = new RevenueOtherCost();
        cost.setYearMonth(month);
        cost.setBusinessLineId(lineId);
        cost.setProjectId(projectId);
        cost.setCostType(type);
        cost.setAmountYuan(new BigDecimal(amount));
        return cost;
    }

    private RevenueDeliverySummaryVO.Line lineOf(RevenueDeliverySummaryVO vo, Long lineId) {
        return vo.getLines().stream().filter(l -> l.getBusinessLineId().equals(lineId)).findFirst().orElseThrow();
    }

    private RevenueDeliverySummaryVO.ProjectRow projectRow(RevenueDeliverySummaryVO.Line line, String name) {
        return line.getProjects().stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
    }

    private void assertNum(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
    }

    // ------------------------------------------------------------------

    @Test
    void royalRowDeliveredEstimatedAndProfitWithToggle() {
        RevenueDeliverySummaryVO vo = service.summary(2026, true, today);
        RevenueDeliverySummaryVO.ProjectRow royal = projectRow(lineOf(vo, 1L), "皇家项目");
        assertNum(royal.getOaContract(), "150000");
        assertThat(royal.getProjectId()).isEqualTo(1L);
        assertThat(royal.getIsAggregate()).isFalse();

        // H1：交付 3 万（3 月）+ 预估 4 万（3 月计划）→ 含预估毛利 1 万
        assertNum(royal.getH1().getDelivered(), "30000");
        assertNum(royal.getH1().getEstimated(), "40000");
        assertNum(royal.getH1().getProjectHours(), "10");
        assertNum(royal.getH1().getProjectLaborCost(), "50000");
        assertNum(royal.getH1().getEstimatedLaborCost(), "10000");
        assertNum(royal.getH1().getLaborProfit(), "10000");
        assertNum(royal.getH1().getGrossProfit(), "10000");
        assertNum(royal.getH1().getGrossRate(), "14.29");
        assertNum(royal.getH1().getOtherCosts().getTotal(), "0");

        // H2：交付 7 万（8 月，<=今天）+ 预估 8 万（10 月计划）− 服务器成本 0.3 万
        assertNum(royal.getH2().getDelivered(), "70000");
        assertNum(royal.getH2().getEstimated(), "80000");
        assertNum(royal.getH2().getEstimatedLaborCost(), "20000");
        assertNum(royal.getH2().getOtherCosts().getServer(), "3000");
        assertNum(royal.getH2().getGrossProfit(), "127000");
        assertNum(royal.getH2().getGrossRate(), "84.67");

        // YTD
        assertNum(royal.getYtd().getDelivered(), "100000");
        assertNum(royal.getYtd().getEstimated(), "120000");
        assertNum(royal.getYtd().getEstimatedLaborCost(), "30000");
        assertNum(royal.getYtd().getGrossProfit(), "137000");
        assertNum(royal.getYtd().getGrossRate(), "62.27");

        // 关预估：只用实际已交付与实际成本
        RevenueDeliverySummaryVO actual = service.summary(2026, false, today);
        RevenueDeliverySummaryVO.ProjectRow royalActual = projectRow(lineOf(actual, 1L), "皇家项目");
        assertNum(royalActual.getYtd().getDelivered(), "100000");
        assertNum(royalActual.getYtd().getEstimated(), "120000");
        assertNum(royalActual.getYtd().getGrossProfit(), "47000");
        assertNum(royalActual.getYtd().getGrossRate(), "47.00");
        assertNum(royalActual.getH1().getGrossProfit(), "-20000");
        assertNum(royalActual.getH1().getGrossRate(), "-66.67");
        assertNum(royalActual.getH2().getGrossProfit(), "67000");
        assertNum(royalActual.getH2().getGrossRate(), "95.71");
    }

    @Test
    void aliasProjectsMergeIntoAoyouRowAndUnclosedMonthIgnored() {
        RevenueDeliverySummaryVO vo = service.summary(2026, true, today);
        RevenueDeliverySummaryVO.Line custom = lineOf(vo, 1L);
        // 行集合：主项目行（别名源佳贝艾特/海普诺凯不注册）
        assertThat(custom.getProjects()).extracting(RevenueDeliverySummaryVO.ProjectRow::getName)
                .containsExactly("皇家项目", "飞鹤", "Speedo", "澳优");
        RevenueDeliverySummaryVO.ProjectRow aoyou = projectRow(custom, "澳优");
        // 佳贝艾特成本并入澳优行
        assertNum(aoyou.getYtd().getProjectHours(), "2");
        assertNum(aoyou.getYtd().getProjectLaborCost(), "12000");
        // 无交付营收 → 毛利率为 null
        assertThat(aoyou.getYtd().getGrossRate()).isNull();
        assertNum(aoyou.getYtd().getGrossProfit(), "-12000");

    }

    @Test
    void salesCostOnlyAtLineLevelAndMemberAggregateRow() {
        RevenueDeliverySummaryVO vo = service.summary(2026, true, today);
        RevenueDeliverySummaryVO.Line custom = lineOf(vo, 1L);
        // 该线全年销售成本标量（sales 行 + 完结月）
        assertNum(custom.getSalesHours(), "1");
        assertNum(custom.getSalesCost(), "8000");
        // 项目行毛利不减销售成本
        RevenueDeliverySummaryVO.ProjectRow royal = projectRow(custom, "皇家项目");
        assertNum(royal.getH1().getSalesCost(), "0");
        assertNum(royal.getH1().getGrossProfit(), "10000");
        // 线 totals：项目毛利合计 − 销售成本
        RevenueDeliverySummaryVO.ProjectRow customTotals = custom.getTotals();
        assertNum(customTotals.getH1().getSalesCost(), "8000");
        assertNum(customTotals.getH1().getGrossProfit(), "-10000");
        assertNum(customTotals.getYtd().getGrossProfit(), "117000");
        assertNum(customTotals.getYtd().getGrossRate(), "53.18");

        // 会员通：业务线聚合行「项目集」+ 线销售成本在 totals 扣减
        RevenueDeliverySummaryVO.Line member = lineOf(vo, 3L);
        RevenueDeliverySummaryVO.ProjectRow agg = projectRow(member, "项目集");
        assertThat(agg.getProjectId()).isNull();
        assertThat(agg.getIsAggregate()).isTrue();
        assertNum(agg.getOaContract(), "600000");
        assertNum(agg.getH1().getDelivered(), "200000");
        assertNum(agg.getH1().getGrossProfit(), "171000");
        assertNum(member.getSalesCost(), "9000");
        RevenueDeliverySummaryVO.ProjectRow memberTotals = member.getTotals();
        assertNum(memberTotals.getH1().getSalesHours(), "2");
        assertNum(memberTotals.getH1().getSalesCost(), "9000");
        assertNum(memberTotals.getH1().getGrossProfit(), "162000");
        assertNum(memberTotals.getH1().getGrossRate(), "81.00");
        assertNum(memberTotals.getYtd().getGrossProfit(), "232000");
        assertNum(memberTotals.getYtd().getGrossRate(), "77.33");
    }

    @Test
    void crossYearDeliveryAndFutureMonthCostExcluded() {
        RevenueDeliverySummaryVO vo = service.summary(2026, true, today);
        RevenueDeliverySummaryVO.Line custom = lineOf(vo, 1L);
        RevenueDeliverySummaryVO.ProjectRow feihe = projectRow(custom, "飞鹤");
        // 交付日期为 2025 年的行不进入 2026 年合同总额或已交付
        assertNum(feihe.getOaContract(), "0");
        assertNum(feihe.getYtd().getDelivered(), "0");
        // 未来交付日期的合同（10-15 > 今天）不构成已交付；12 月服务器成本不计
        RevenueDeliverySummaryVO.ProjectRow royal = projectRow(custom, "皇家项目");
        assertNum(royal.getYtd().getDelivered(), "100000");
        assertNum(royal.getH2().getOtherCosts().getServer(), "3000");
        assertNum(royal.getYtd().getOtherCosts().getTotal(), "3000");
    }
    @Test
    void nonRevenueLinesAreOmittedAndOverviewOnlyAggregatesIncludedLines() {
        RevenueDeliverySummaryVO vo = service.summary(2026, true, today);

        assertThat(vo.getLines()).extracting(RevenueDeliverySummaryVO.Line::getBusinessLineName)
                .containsExactly("全渠道云鹿定制", "会员通", "全域精准")
                .doesNotContain("海外业务线", "全渠道产品");
        // 排除线中仍有合同 7 万、项目/销售成本 5.7 万，概览只能汇总纳入的收入业务线。
        assertNum(vo.getOverview().getTotalOaContract(), "750000");
        assertNum(vo.getOverview().getTotalDelivered(), "300000");
        assertNum(vo.getOverview().getTotalLaborCost(), "163000");
        assertNum(vo.getOverview().getTotalProfit(), "349000");
    }


    @Test
    void overviewAggregatesWholeTableWithToggle() {
        RevenueDeliverySummaryVO vo = service.summary(2026, true, today);
        assertThat(vo.getIncludeEstimate()).isTrue();
        // 仅统计纳入业务线的 2026 交付日期合同：15万皇家 + 60万会员通
        assertNum(vo.getOverview().getTotalOaContract(), "750000");
        assertNum(vo.getOverview().getTotalDelivered(), "300000");
        assertNum(vo.getOverview().getTotalEstimated(), "220000");
        assertNum(vo.getOverview().getTotalLaborCost(), "163000");
        assertNum(vo.getOverview().getTotalOtherCost(), "8000");
        assertNum(vo.getOverview().getTotalProfit(), "349000");
        assertNum(vo.getOverview().getProfitRate(), "67.12");

        RevenueDeliverySummaryVO actual = service.summary(2026, false, today);
        assertThat(actual.getIncludeEstimate()).isFalse();
        assertNum(actual.getOverview().getTotalDelivered(), "300000");
        assertNum(actual.getOverview().getTotalLaborCost(), "103000");
        assertNum(actual.getOverview().getTotalProfit(), "189000");
        assertNum(actual.getOverview().getProfitRate(), "63.00");
    }

    @Test
    void futureDeliveryPlanAndFutureOtherCostStoredButNotYetActual() {
        // 计划含未来月份 12 月的服务器成本被过滤、而计划交付金额照常计入预估
        when(otherCostMapper.selectList(any())).thenReturn(List.of(other("2026-12", 1L, 1L, "server", "9999")));
        when(planMapper.selectList(any())).thenReturn(List.of(plan("2026-12", 1L, 1L, "80000", "20000")));
        when(contractEntryMapper.selectList(any())).thenReturn(List.of());
        when(worklogEntryMapper.selectList(any())).thenReturn(List.of());
        when(costEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueDeliverySummaryVO vo = service.summary(2026, true, today);
        RevenueDeliverySummaryVO.ProjectRow royal = projectRow(lineOf(vo, 1L), "皇家项目");
        assertNum(royal.getH2().getEstimated(), "80000");
        assertNum(royal.getH2().getEstimatedLaborCost(), "20000");
        assertNum(royal.getH2().getOtherCosts().getServer(), "0");
        assertNum(royal.getYtd().getOtherCosts().getTotal(), "0");
    }

    @Test
    void emptyBusinessLinesReturnZeroedOverview() {
        when(businessLineMapper.selectList(any())).thenReturn(List.of());
        RevenueDeliverySummaryVO vo = service.summary(2026, true, today);
        assertThat(vo.getLines()).isEmpty();
        assertNum(vo.getOverview().getTotalOaContract(), "0");
        assertThat(vo.getOverview().getProfitRate()).isNull();
    }

    @Test
    void fullModeLineLevelProjectCostRowsDoNotPolluteProjectMargins() {
        // 定制线业务线级【项目】行（projectId=null）在矩阵并入 other，不进任何项目行毛利
        when(worklogEntryMapper.selectList(any())).thenReturn(List.of());
        when(costEntryMapper.selectList(any())).thenReturn(List.of(
                cost("2026-03", 1L, null, "3", "15000", "project")));
        RevenueDeliverySummaryVO vo = service.summary(2026, true, today);
        RevenueDeliverySummaryVO.Line custom = lineOf(vo, 1L);
        assertThat(custom.getProjects()).extracting(RevenueDeliverySummaryVO.ProjectRow::getName)
                .containsExactly("皇家项目", "飞鹤", "Speedo", "澳优");
        for (RevenueDeliverySummaryVO.ProjectRow row : custom.getProjects()) {
            assertNum(row.getYtd().getProjectLaborCost(), "0");
            assertNum(row.getYtd().getProjectHours(), "0");
        }
    }

    // ---------------------------------------------------------------- 销售成本成单分配


    @Test
    void salesAllocationOnlyWithBoundOpportunityAndUniqueClosedDeal() {
        when(worklogEntryMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(otherCostMapper.selectList(any())).thenReturn(List.of());
        // 合同（客户成单证据）：雀巢→皇家项目；澳优→澳优+飞鹤（同客户多项目）
        RevenueContractEntry c1 = contract("c1", 1L, 1L, "100000", "2026-03", "2026-03-31");
        c1.setCustomer("雀巢公司");
        RevenueContractEntry c2 = contract("c2", 1L, 24L, "60000", "2026-05", "2026-05-31");
        c2.setCustomer("澳优");
        RevenueContractEntry c3 = contract("c3", 1L, 7L, "50000", "2026-06", "2026-06-30");
        c3.setCustomer("澳优");
        when(contractEntryMapper.selectList(any())).thenReturn(List.of(c1, c2, c3));

        RevenueSalesProject spRoyal = new RevenueSalesProject();
        spRoyal.setId(1L);
        spRoyal.setBusinessLineId(1L);
        spRoyal.setName("皇家CDP商机");
        spRoyal.setOpportunityId(10L);
        RevenueSalesProject spUnbound = new RevenueSalesProject();
        spUnbound.setId(2L);
        spUnbound.setBusinessLineId(1L);
        spUnbound.setName("无商机销售");
        RevenueSalesProject spNoDeal = new RevenueSalesProject();
        spNoDeal.setId(3L);
        spNoDeal.setBusinessLineId(1L);
        spNoDeal.setName("未成单销售");
        spNoDeal.setOpportunityId(12L);
        when(salesProjectMapper.selectList(null)).thenReturn(List.of(spRoyal, spUnbound, spNoDeal));

        SalesOpportunity oppRoyal = new SalesOpportunity();
        oppRoyal.setId(10L);
        oppRoyal.setCustomer("雀巢公司");
        SalesOpportunity oppNoDeal = new SalesOpportunity();
        oppNoDeal.setId(12L);
        oppNoDeal.setCustomer("从未成单客户");
        when(opportunityMapper.selectList(null)).thenReturn(List.of(oppRoyal, oppNoDeal));

        when(projectMapper.selectList(null)).thenReturn(List.of(
                project(1L, 1L, null, "皇家项目"),
                project(7L, 1L, null, "飞鹤"),
                project(8L, 1L, null, "佳贝艾特"),
                project(24L, 1L, null, "澳优")));

        // 销售成本行：specific(sp1, opp10=雀巢) ×2 → 唯一合同项目=皇家（已分配）；
        // pool → 商机集合无证据；specific(sp2 无商机绑定) → NO_OPP_LINK；
        // specific(sp3, opp12 无合同) → NO_MATCH_CONTRACT
        List<RevenueCostEntry> costs = new ArrayList<>();
        costs.add(cost("2026-03", 1L, 1L, "10", "50000", "project"));
        RevenueCostEntry allocA = cost("2026-03", 1L, 1L, "0.2", "6000", "sales");
        allocA.setSalesKind("specific");
        allocA.setSalesProjectId(1L);
        costs.add(allocA);
        RevenueCostEntry allocB = cost("2026-04", 1L, 1L, "0.1", "3000", "sales");
        allocB.setSalesKind("specific");
        allocB.setSalesProjectId(1L);
        costs.add(allocB);
        RevenueCostEntry pool = cost("2026-03", 1L, 1L, "0.5", "15000", "sales");
        pool.setSalesKind("pool");
        costs.add(pool);
        RevenueCostEntry unbound = cost("2026-03", 1L, 1L, "0.3", "9000", "sales");
        unbound.setSalesKind("specific");
        unbound.setSalesProjectId(2L);
        costs.add(unbound);
        RevenueCostEntry noDeal = cost("2026-05", 1L, 1L, "0.1", "2000", "sales");
        noDeal.setSalesKind("specific");
        noDeal.setSalesProjectId(3L);
        costs.add(noDeal);
        when(costEntryMapper.selectList(any())).thenReturn(costs);

        RevenueDeliverySummaryVO vo = service.summary(2026, true, today);
        RevenueDeliverySummaryVO.Line custom = lineOf(vo, 1L);
        RevenueDeliverySummaryVO.ProjectRow royal = projectRow(custom, "皇家项目");
        // 已分配 9000 落入皇家项目行
        assertNum(royal.getYtd().getAllocatedSalesCost(), "9000");
        assertNum(royal.getYtd().getAllocatedSalesHours(), "0.3");
        // 项目真实利润 = 毛利 − 已分配销售成本（毛利 5 万 = 交付10万 − 项目人工5万）
        assertNum(royal.getYtd().getGrossProfit(), "50000");
        assertNum(royal.getYtd().getTrueProfit(), "41000");

        // 该线销售成本 35000 = 已分配 9000 + 未分配 26000（pool15000+无商机9000+无合同2000）
        assertNum(custom.getSalesCost(), "35000");
        assertNum(custom.getSalesAllocatedCost(), "9000");
        assertNum(custom.getSalesUnallocatedCost(), "26000");
        assertThat(custom.getSalesUnallocatedDetail())
                .extracting(item -> item.getReason())
                .contains("POOL_NO_EVIDENCE", "NO_OPP_LINK", "NO_MATCH_CONTRACT");

        RevenueDeliverySummaryVO.ProjectRow totals = custom.getTotals();
        assertNum(totals.getYtd().getUnallocatedSalesCost(), "26000");
        // 业务线真实利润 = Σ项目真实利润 − 未分配销售成本 = (41000+60000+50000) − 26000 = 125000
        assertNum(totals.getYtd().getTrueProfit(), "125000");
        assertNum(vo.getOverview().getTotalAllocatedSalesCost(), "9000");
        assertNum(vo.getOverview().getTotalUnallocatedSalesCost(), "26000");
        assertNum(vo.getOverview().getTotalTrueProfit(), "125000");
    }
    @Test
    void deliveryYearIsIndependentOfSaleMonthAndNullDateIsExcluded() {
        RevenueContractEntry crossMonth = contract("cross-month", 1L, 1L, "1234", "2025-12", "2026-02-10");
        RevenueContractEntry noDate = contract("no-date", 1L, 1L, "5678", "2026-02", null);
        when(contractEntryMapper.selectList(any())).thenReturn(List.of(crossMonth, noDate));
        RevenueDeliverySummaryVO vo = service.summary(2026, false, today);
        RevenueDeliverySummaryVO.ProjectRow royal = projectRow(lineOf(vo, 1L), "皇家项目");
        assertNum(royal.getOaContract(), "1234");
        assertNum(royal.getYtd().getDelivered(), "1234");
        assertNum(vo.getOverview().getTotalNoDeliveryDateContract(), "5678");
    }

    @Test
    void fullLineUnallocatedContractIsInTotalsButNotProjectRows() {
        RevenueContractEntry lineContract = contract("futian", 1L, null, "4650", "2025-12", "2026-03-01");
        when(contractEntryMapper.selectList(any())).thenReturn(List.of(lineContract));
        when(otherCostMapper.selectList(any())).thenReturn(List.of(other("2026-03", 1L, null, "server", "100")));
        RevenueDeliverySummaryVO vo = service.summary(2026, false, today);
        RevenueDeliverySummaryVO.Line custom = lineOf(vo, 1L);
        assertNum(custom.getLineUnallocatedContract(), "4650");
        assertNum(custom.getLineUnallocatedDelivered(), "4650");
        // line profit = delivered 4650 − line sales cost 8000 − line other cost 100
        assertNum(custom.getTotals().getLineUnallocatedProfit(), "-3450");
        assertNum(custom.getTotals().getLineUnallocatedContract(), "4650");
        assertNum(custom.getTotals().getLineUnallocatedDelivered(), "4650");
        assertNum(custom.getTotals().getYtd().getOtherCosts().getServer(), "100");
        assertNum(custom.getTotals().getYtd().getGrossProfit(), "-65450");
        assertNum(custom.getTotals().getYtd().getTrueProfit(), "-65450");
    }
}
