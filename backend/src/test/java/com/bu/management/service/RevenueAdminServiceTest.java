package com.bu.management.service;

import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueEstimateEntry;
import com.bu.management.entity.RevenueWorklogEntry;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueMonthCloseMapper;
import com.bu.management.mapper.RevenueNameMappingMapper;
import com.bu.management.mapper.RevenueEstimateEntryMapper;
import com.bu.management.mapper.RevenueSalesProjectMapper;
import com.bu.management.mapper.RevenueWorklogEntryMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueAdminServiceTest {

    @Mock private RevenueEstimateEntryMapper estimateEntryMapper;
    @Mock private RevenueWorklogEntryMapper worklogEntryMapper;
    @Mock private RevenueCostEntryMapper costEntryMapper;
    @Mock private RevenueSalesProjectMapper salesProjectMapper;
    @Mock private SalesOpportunityMapper opportunityMapper;
    @Mock private RevenueMonthCloseMapper monthCloseMapper;
    @Mock private RevenueMappingResolver mappingResolver;
    @Mock private RevenueNameMappingMapper nameMappingMapper;

    private RevenueAdminService service;

    @BeforeEach
    void setUp() {
        service = serviceWithClosed();
    }

    private RevenueAdminService serviceWithClosed(String... closed) {
        Set<String> closedSet = Set.of(closed);
        RevenueMonthService monthService = new RevenueMonthService(monthCloseMapper) {
            @Override
            public boolean isClosed(String yearMonth) {
                return closedSet.contains(yearMonth);
            }
        };
        return new RevenueAdminService(estimateEntryMapper, worklogEntryMapper, costEntryMapper,
                salesProjectMapper, opportunityMapper, monthService, mappingResolver, nameMappingMapper);
    }

    private RevenueCostEntry closedCost(Long projectId, String hours, String cost) {
        RevenueCostEntry entry = new RevenueCostEntry();
        entry.setYearMonth("2026-07");
        entry.setProjectId(projectId);
        entry.setHours(new BigDecimal(hours));
        entry.setCostAmount(new BigDecimal(cost));
        entry.setPending(0);
        return entry;
    }

    private RevenueEstimateEntry estimateRequest() {
        RevenueEstimateEntry request = new RevenueEstimateEntry();
        request.setYearMonth("2026-09");
        request.setBusinessLineId(1L);
        request.setProjectId(11L);
        request.setWorkType("project");
        request.setDescription("黄天鹅物码项目支持");
        request.setPersonMonths(new BigDecimal("1.5"));
        return request;
    }

    @Test
    void unitPriceAveragesAllClosedMonths() {
        when(costEntryMapper.selectList(any())).thenReturn(List.of(
                closedCost(11L, "2.0", "40000"),
                closedCost(11L, "3.0", "66000")
        ));
        service = serviceWithClosed("2026-07");
        assertThat(service.historicalUnitPrice(11L)).isEqualByComparingTo("21200.00");
    }

    @Test
    void unitPriceNullWithoutClosedHistory() {
        when(costEntryMapper.selectList(any())).thenReturn(List.of(closedCost(11L, "2.0", "40000")));
        assertThat(service.historicalUnitPrice(11L)).isNull();
    }

    @Test
    void createEstimateSnapshotsUnitPriceAndComputesAmount() {
        when(costEntryMapper.selectList(any())).thenReturn(List.of(closedCost(11L, "2.0", "40000")));
        service = serviceWithClosed("2026-07");

        RevenueEstimateEntry created = service.createEstimate(estimateRequest(), 16L);
        assertThat(created.getUnitPrice()).isEqualByComparingTo("20000.00");
        assertThat(created.getAmount()).isEqualByComparingTo("30000.00");
        assertThat(created.getCreatedBy()).isEqualTo(16L);
    }

    @Test
    void createEstimateWithoutHistoryKeepsAmountNull() {
        when(costEntryMapper.selectList(any())).thenReturn(List.of());

        RevenueEstimateEntry created = service.createEstimate(estimateRequest(), 16L);
        assertThat(created.getUnitPrice()).isNull();
        assertThat(created.getAmount()).isNull();
    }

    @Test
    void closedMonthRejectsEstimateMutation() {
        service = serviceWithClosed("2026-09");
        assertThatThrownBy(() -> service.createEstimate(estimateRequest(), 16L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已完结");
    }

    @Test
    void manualWorklogEntryBypassesClosureAndTagsPool() {
        service = serviceWithClosed("2026-07");   // 完结月也允许手工补录
        RevenueWorklogEntry request = new RevenueWorklogEntry();
        request.setYearMonth("2026-07");
        request.setBusinessLineId(1L);
        request.setWorkType("sales");
        request.setSalesKind("pool");
        request.setHours(new BigDecimal("0.5"));
        request.setWorkNote("皇家续费沟通");
        when(mappingResolver.tagWorkNote("皇家续费沟通")).thenReturn("皇家");

        RevenueWorklogEntry created = service.createWorklogEntry(request, 16L);
        assertThat(created.getBatchId()).isNull();
        assertThat(created.getPending()).isEqualTo(0);
        assertThat(created.getCreatedBy()).isEqualTo(16L);
        assertThat(created.getTags()).isEqualTo("皇家");
    }

    @Test
    void updateWorklogEntryPreservesBatchAndCreator() {
        RevenueWorklogEntry existing = new RevenueWorklogEntry();
        existing.setId(5L);
        existing.setBatchId(3L);
        existing.setPending(0);
        existing.setCreatedBy(7L);
        when(worklogEntryMapper.selectById(5L)).thenReturn(existing);
        when(worklogEntryMapper.updateById(any())).thenReturn(1);

        RevenueWorklogEntry request = new RevenueWorklogEntry();
        request.setYearMonth("2026-07");
        request.setBusinessLineId(1L);
        request.setWorkType("project");
        request.setHours(new BigDecimal("0.8"));
        service.updateWorklogEntry(5L, request);
        assertThat(request.getBatchId()).isEqualTo(3L);
        assertThat(request.getCreatedBy()).isEqualTo(7L);
    }

    @Test
    void manualCostEntryValidatesAmounts() {
        RevenueCostEntry request = new RevenueCostEntry();
        request.setYearMonth("2026-07");
        request.setBusinessLineId(1L);
        request.setHours(new BigDecimal("0.5"));
        request.setCostAmount(new BigDecimal("-1"));
        assertThatThrownBy(() -> service.createCostEntry(request, 16L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvePendingAssignsBusinessLineAndProject() {
        RevenueWorklogEntry entry = new RevenueWorklogEntry();
        entry.setId(5L);
        entry.setWorkType("project");
        entry.setPending(1);
        when(worklogEntryMapper.selectById(5L)).thenReturn(entry);

        service.resolvePending("worklog", 5L, 1L, 11L, 16L);
        assertThat(entry.getBusinessLineId()).isEqualTo(1L);
        assertThat(entry.getProjectId()).isEqualTo(11L);
        assertThat(entry.getPending()).isEqualTo(0);
    }
}
