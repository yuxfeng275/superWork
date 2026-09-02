package com.bu.management.service;

import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueOtherCost;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueOtherCostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueOtherCostServiceTest {

    @Mock private RevenueOtherCostMapper otherCostMapper;
    @Mock private BusinessLineMapper businessLineMapper;
    @Mock private ProjectMapper projectMapper;

    private RevenueOtherCostService service;

    @BeforeEach
    void setUp() {
        service = new RevenueOtherCostService(otherCostMapper, businessLineMapper, projectMapper);
        lenient().when(businessLineMapper.selectById(1L)).thenReturn(line(1L, "全渠道云鹿定制"));
        lenient().when(projectMapper.selectById(11L)).thenReturn(project(11L, 1L));
        lenient().when(businessLineMapper.selectById(3L)).thenReturn(line(3L, "会员通"));
    }

    private BusinessLine line(Long id, String name) {
        BusinessLine line = new BusinessLine();
        line.setId(id);
        line.setName(name);
        line.setRevenueMode("full");
        return line;
    }

    private Project project(Long id, Long lineId) {
        Project project = new Project();
        project.setId(id);
        project.setBusinessLineId(lineId);
        project.setName("皇家项目");
        return project;
    }

    private RevenueOtherCost request(Long lineId, Long projectId, String type) {
        RevenueOtherCost cost = new RevenueOtherCost();
        cost.setYearMonth("2026-08");
        cost.setBusinessLineId(lineId);
        cost.setProjectId(projectId);
        cost.setCostType(type);
        cost.setAmountYuan(new BigDecimal("20000"));
        return cost;
    }

    @Test
    void createValidatesAndStores() {
        RevenueOtherCost created = service.create(request(1L, 11L, "server"), 5L);
        assertThat(created.getCreatedBy()).isEqualTo(5L);
        assertThat(created.getId()).isNull();
        verify(otherCostMapper).insert(created);
    }

    @Test
    void lineLevelOtherCostAllowedOnAggregateLine() {
        RevenueOtherCost cost = request(3L, null, "partner");
        RevenueOtherCost created = service.create(cost, 1L);
        assertThat(created.getProjectId()).isNull();
    }

    @Test
    void rejectsBadCostTypeBadMonthBadAmountAndForeignProject() {
        assertThatThrownBy(() -> service.create(request(1L, 11L, "weird"), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partner/server/other");
        RevenueOtherCost badMonth = request(1L, 11L, "server");
        badMonth.setYearMonth("2026-13");
        assertThatThrownBy(() -> service.create(badMonth, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM");
        RevenueOtherCost negative = request(1L, 11L, "server");
        negative.setAmountYuan(new BigDecimal("-1"));
        assertThatThrownBy(() -> service.create(negative, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为负");
        when(projectMapper.selectById(24L)).thenReturn(project(24L, 3L));
        RevenueOtherCost foreign = request(1L, 24L, "server");
        assertThatThrownBy(() -> service.create(foreign, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目不属于该业务线");
        RevenueOtherCost missingProject = request(1L, 99L, "server");
        when(projectMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.create(missingProject, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("项目不存在");
    }

    @Test
    void updatePreservesAuditFieldsAndOverwrites() {
        RevenueOtherCost existing = request(1L, 11L, "server");
        existing.setId(7L);
        existing.setAmountYuan(new BigDecimal("9999"));
        existing.setCreatedBy(2L);
        existing.setCreatedAt(LocalDateTime.now());
        when(otherCostMapper.selectById(7L)).thenReturn(existing);

        RevenueOtherCost request = request(1L, 11L, "other");
        request.setNote("追加");
        service.update(7L, request);

        ArgumentCaptor<RevenueOtherCost> captor = ArgumentCaptor.forClass(RevenueOtherCost.class);
        verify(otherCostMapper).updateById(captor.capture());
        RevenueOtherCost updated = captor.getValue();
        assertThat(updated.getId()).isEqualTo(7L);
        assertThat(updated.getCreatedBy()).isEqualTo(2L);
        assertThat(updated.getCostType()).isEqualTo("other");
        assertThat(updated.getNote()).isEqualTo("追加");
    }

    @Test
    void updateAndDeleteRequireExisting() {
        when(otherCostMapper.selectById(8L)).thenReturn(null);
        assertThatThrownBy(() -> service.update(8L, new RevenueOtherCost()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.delete(8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        when(otherCostMapper.selectById(9L)).thenReturn(new RevenueOtherCost());
        service.delete(9L);
        verify(otherCostMapper).deleteById(9L);
    }

    @Test
    void listUsesFilters() {
        lenient().when(otherCostMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.list("2026-08", 1L, 11L, "server")).isEmpty();
        assertThat(service.list(null, null, null, null)).isEmpty();
    }
}
