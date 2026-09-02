package com.bu.management.service;

import com.bu.management.dto.RevenuePlanBatchRequest;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueDeliveryPlan;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueDeliveryPlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueDeliveryPlanServiceTest {

    @Mock private RevenueDeliveryPlanMapper planMapper;
    @Mock private RevenueCostEntryMapper costEntryMapper;
    @Mock private BusinessLineMapper businessLineMapper;
    @Mock private ProjectMapper projectMapper;

    private RevenueDeliveryPlanService service;

    @BeforeEach
    void setUp() {
        service = serviceWithClosed("2026-03", "2026-07");
    }

    private RevenueDeliveryPlanService serviceWithClosed(String... closed) {
        Set<String> closedSet = Set.of(closed);
        RevenueMonthService monthService = new RevenueMonthService(null) {
            @Override
            public boolean isClosed(String yearMonth) {
                return closedSet.contains(yearMonth);
            }
        };
        return new RevenueDeliveryPlanService(planMapper, costEntryMapper, monthService,
                businessLineMapper, projectMapper);
    }

    private BusinessLine line(Long id, String name, String mode) {
        BusinessLine line = new BusinessLine();
        line.setId(id);
        line.setName(name);
        line.setRevenueMode(mode);
        return line;
    }

    private Project project(Long id, Long lineId, String name) {
        Project project = new Project();
        project.setId(id);
        project.setBusinessLineId(lineId);
        project.setName(name);
        return project;
    }

    private RevenueCostEntry cost(String month, Long lineId, Long projectId, String hours, String amount) {
        RevenueCostEntry entry = new RevenueCostEntry();
        entry.setYearMonth(month);
        entry.setBusinessLineId(lineId);
        entry.setProjectId(projectId);
        entry.setWorkType("project");
        entry.setHours(new BigDecimal(hours));
        entry.setCostAmount(new BigDecimal(amount));
        entry.setPending(0);
        return entry;
    }

    @Test
    void createSnapshotsProjectUnitPriceAndComputesLaborCost() {
        when(businessLineMapper.selectById(1L)).thenReturn(line(1L, "全渠道云鹿定制", "full"));
        when(projectMapper.selectById(11L)).thenReturn(project(11L, 1L, "皇家项目"));
        when(costEntryMapper.selectList(any())).thenReturn(List.of(
                cost("2026-03", 1L, 11L, "2.0", "40000"),
                cost("2026-07", 1L, 11L, "3.0", "66000")));

        RevenueDeliveryPlan request = new RevenueDeliveryPlan();
        request.setYearMonth("2026-10");
        request.setBusinessLineId(1L);
        request.setProjectId(11L);
        request.setAmountYuan(new BigDecimal("85108"));
        request.setPersonMonths(new BigDecimal("2.5"));

        RevenueDeliveryPlan saved = service.create(request, 5L);

        assertThat(saved.getUnitPriceSnapshot()).isEqualByComparingTo("21200.00");
        assertThat(saved.getLaborCostYuan()).isEqualByComparingTo("53000.00");
        assertThat(saved.getAmountYuan()).isEqualByComparingTo("85108");
        assertThat(saved.getCreatedBy()).isEqualTo(5L);
        verify(planMapper).insert(saved);
    }

    @Test
    void createWithoutHistoryKeepsSnapshotAndLaborNull() {
        when(businessLineMapper.selectById(1L)).thenReturn(line(1L, "全渠道云鹿定制", "full"));
        when(projectMapper.selectById(11L)).thenReturn(project(11L, 1L, "皇家项目"));
        when(costEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueDeliveryPlan request = new RevenueDeliveryPlan();
        request.setYearMonth("2026-10");
        request.setBusinessLineId(1L);
        request.setProjectId(11L);
        request.setAmountYuan(new BigDecimal("10000"));
        request.setPersonMonths(new BigDecimal("1"));

        RevenueDeliveryPlan saved = service.create(request, 5L);
        assertThat(saved.getUnitPriceSnapshot()).isNull();
        assertThat(saved.getLaborCostYuan()).isNull();
    }

    @Test
    void createBatchComputesPerRowLaborAndRejectsWrongYearMonth() {
        when(businessLineMapper.selectById(1L)).thenReturn(line(1L, "全渠道云鹿定制", "full"));
        when(projectMapper.selectById(11L)).thenReturn(project(11L, 1L, "皇家项目"));
        when(costEntryMapper.selectList(any())).thenReturn(List.of(cost("2026-03", 1L, 11L, "1", "21000")));

        RevenuePlanBatchRequest batch = new RevenuePlanBatchRequest();
        batch.setBusinessLineId(1L);
        batch.setProjectId(11L);
        batch.setYear(2026);
        RevenuePlanBatchRequest.Row r1 = new RevenuePlanBatchRequest.Row();
        r1.setYearMonth("2026-09");
        r1.setAmountYuan(new BigDecimal("50000"));
        r1.setPersonMonths(new BigDecimal("2"));
        RevenuePlanBatchRequest.Row r2 = new RevenuePlanBatchRequest.Row();
        r2.setYearMonth("2026-10");
        r2.setAmountYuan(new BigDecimal("60000"));
        r2.setPersonMonths(new BigDecimal("1"));
        batch.setRows(List.of(r1, r2));

        List<RevenueDeliveryPlan> created = service.createBatch(batch, 5L);
        assertThat(created).hasSize(2);
        assertThat(created.get(0).getLaborCostYuan()).isEqualByComparingTo("42000.00");
        assertThat(created.get(1).getLaborCostYuan()).isEqualByComparingTo("21000.00");

        r1.setYearMonth("2027-01");
        assertThatThrownBy(() -> service.createBatch(batch, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于年份");
    }

    @Test
    void aggregateLineLevelPlanUsesLineProjectUnitPrice() {
        // 会员通业务线聚合行（projectId 空）：单价取该业务线 project 类完结成本/人月
        when(businessLineMapper.selectById(3L)).thenReturn(line(3L, "会员通", "aggregate"));
        when(costEntryMapper.selectList(any())).thenReturn(List.of(
                cost("2026-03", 3L, null, "5.0", "100000")));

        RevenueDeliveryPlan request = new RevenueDeliveryPlan();
        request.setYearMonth("2026-11");
        request.setBusinessLineId(3L);
        request.setProjectId(null);
        request.setAmountYuan(new BigDecimal("80000"));
        request.setPersonMonths(new BigDecimal("3"));

        RevenueDeliveryPlan saved = service.create(request, 5L);
        assertThat(saved.getUnitPriceSnapshot()).isEqualByComparingTo("20000.00");
        assertThat(saved.getLaborCostYuan()).isEqualByComparingTo("60000.00");
    }

    @Test
    void fullModeLineRejectsLineLevelPlanAndForeignProject() {
        when(businessLineMapper.selectById(1L)).thenReturn(line(1L, "全渠道云鹿定制", "full"));
        RevenueDeliveryPlan request = new RevenueDeliveryPlan();
        request.setYearMonth("2026-09");
        request.setBusinessLineId(1L);
        request.setProjectId(null);
        request.setAmountYuan(new BigDecimal("1000"));
        request.setPersonMonths(new BigDecimal("1"));
        assertThatThrownBy(() -> service.create(request, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无业务线级聚合行");

        when(projectMapper.selectById(24L)).thenReturn(project(24L, 3L, "澳优"));
        request.setProjectId(24L);
        assertThatThrownBy(() -> service.create(request, 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目不属于该业务线");
    }

    @Test
    void updateRecomputesLaborFromSnapshotWhenPersonMonthsChanges() {
        RevenueDeliveryPlan existing = new RevenueDeliveryPlan();
        existing.setId(9L);
        existing.setYearMonth("2026-10");
        existing.setBusinessLineId(1L);
        existing.setProjectId(11L);
        existing.setUnitPriceSnapshot(new BigDecimal("21200.00"));
        existing.setPersonMonths(new BigDecimal("1"));
        existing.setAmountYuan(new BigDecimal("85108"));
        when(planMapper.selectById(9L)).thenReturn(existing);
        when(businessLineMapper.selectById(1L)).thenReturn(line(1L, "全渠道云鹿定制", "full"));
        when(projectMapper.selectById(11L)).thenReturn(project(11L, 1L, "皇家项目"));

        RevenueDeliveryPlan request = new RevenueDeliveryPlan();
        request.setAmountYuan(new BigDecimal("100000"));
        request.setPersonMonths(new BigDecimal("3"));

        service.update(9L, request);

        ArgumentCaptor<RevenueDeliveryPlan> captor = ArgumentCaptor.forClass(RevenueDeliveryPlan.class);
        verify(planMapper).updateById(captor.capture());
        RevenueDeliveryPlan merged = captor.getValue();
        assertThat(merged.getLaborCostYuan()).isEqualByComparingTo("63600.00");
        assertThat(merged.getPersonMonths()).isEqualByComparingTo("3");
        assertThat(merged.getAmountYuan()).isEqualByComparingTo("100000");
        assertThat(merged.getYearMonth()).isEqualTo("2026-10");
        assertThat(merged.getUnitPriceSnapshot()).isEqualByComparingTo("21200.00");
    }

    @Test
    void updateRequiresExistingAndValidates() {
        when(planMapper.selectById(88L)).thenReturn(null);
        assertThatThrownBy(() -> service.update(88L, new RevenueDeliveryPlan()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");

        RevenueDeliveryPlan existing = new RevenueDeliveryPlan();
        existing.setId(1L);
        existing.setYearMonth("2026-09");
        existing.setBusinessLineId(3L);
        existing.setProjectId(null);
        when(planMapper.selectById(1L)).thenReturn(existing, existing);
        when(businessLineMapper.selectById(3L)).thenReturn(line(3L, "会员通", "aggregate"));
        RevenueDeliveryPlan request = new RevenueDeliveryPlan();
        request.setAmountYuan(new BigDecimal("-5"));
        assertThatThrownBy(() -> service.update(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为负");
    }

    @Test
    void deleteRemovesExistingOnly() {
        when(planMapper.selectById(3L)).thenReturn(new RevenueDeliveryPlan());
        service.delete(3L);
        verify(planMapper).deleteById(3L);
        when(planMapper.selectById(4L)).thenReturn(null);
        assertThatThrownBy(() -> service.delete(4L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void listUsesOptionalFilters() {
        lenient().when(planMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.list(2026, null, null)).isEmpty();
        assertThat(service.list(null, 1L, 11L)).isEmpty();
    }

    @Test
    void badMonthFormatRejected() {
        RevenueDeliveryPlan request = new RevenueDeliveryPlan();
        request.setYearMonth("2026/09");
        request.setBusinessLineId(1L);
        request.setProjectId(11L);
        request.setAmountYuan(new BigDecimal("1"));
        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM");
    }
}
