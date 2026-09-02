package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueOtherCost;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueOtherCostMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 其他成本手动维护：按月×业务线×项目（项目可空=业务线级）×类型（partner/server/other）。
 */
@Service
@RequiredArgsConstructor
public class RevenueOtherCostService {

    private static final Set<String> COST_TYPES = Set.of("partner", "server", "other");

    private final RevenueOtherCostMapper otherCostMapper;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;

    public List<RevenueOtherCost> list(String yearMonth, Long businessLineId, Long projectId, String costType) {
        return otherCostMapper.selectList(new LambdaQueryWrapper<RevenueOtherCost>()
                .eq(StringUtils.hasText(yearMonth), RevenueOtherCost::getYearMonth, yearMonth)
                .eq(businessLineId != null, RevenueOtherCost::getBusinessLineId, businessLineId)
                .eq(projectId != null, RevenueOtherCost::getProjectId, projectId)
                .eq(StringUtils.hasText(costType), RevenueOtherCost::getCostType, costType)
                .orderByDesc(RevenueOtherCost::getYearMonth)
                .orderByDesc(RevenueOtherCost::getId));
    }

    public RevenueOtherCost create(RevenueOtherCost request, Long userId) {
        validate(request);
        request.setId(null);
        request.setCreatedBy(userId);
        otherCostMapper.insert(request);
        return request;
    }

    public RevenueOtherCost update(Long id, RevenueOtherCost request) {
        RevenueOtherCost existing = require(id);
        validate(request);
        request.setId(id);
        request.setCreatedBy(existing.getCreatedBy());
        request.setCreatedAt(existing.getCreatedAt());
        request.setUpdatedAt(LocalDateTime.now());
        otherCostMapper.updateById(request);
        return otherCostMapper.selectById(id);
    }

    public void delete(Long id) {
        if (otherCostMapper.selectById(id) == null) {
            throw new IllegalArgumentException("其他成本记录不存在");
        }
        otherCostMapper.deleteById(id);
    }

    private RevenueOtherCost require(Long id) {
        RevenueOtherCost cost = otherCostMapper.selectById(id);
        if (cost == null) {
            throw new IllegalArgumentException("其他成本记录不存在");
        }
        return cost;
    }

    private void validate(RevenueOtherCost request) {
        RevenueDeliveryPlanService.requireMonth(request.getYearMonth());
        if (request.getBusinessLineId() == null) {
            throw new IllegalArgumentException("业务线不能为空");
        }
        if (request.getCostType() == null || !COST_TYPES.contains(request.getCostType())) {
            throw new IllegalArgumentException("成本类型必须为 partner/server/other 之一");
        }
        if (request.getAmountYuan() == null || request.getAmountYuan().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("金额不能为空且不能为负");
        }
        BusinessLine line = businessLineMapper.selectById(request.getBusinessLineId());
        if (line == null) {
            throw new IllegalArgumentException("业务线不存在");
        }
        if (request.getProjectId() != null) {
            Project project = projectMapper.selectById(request.getProjectId());
            if (project == null) {
                throw new IllegalArgumentException("项目不存在");
            }
            if (!request.getBusinessLineId().equals(project.getBusinessLineId())) {
                throw new IllegalArgumentException("项目不属于该业务线");
            }
        }
    }
}
