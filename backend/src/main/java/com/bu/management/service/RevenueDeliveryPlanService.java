package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.RevenuePlanBatchRequest;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueDeliveryPlan;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueDeliveryPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 预估交付计划维护：按 项目×月份 录入预估交付金额与预估人月；
 * 预估工时成本 = 预估人月 × 该项目「历史完结累计单价」快照（沿用营收预估口径）。
 * projectId 为空 = 业务线聚合行（会员通项目集）：单价取该业务线 project 类完结成本/人月。
 */
@Service
@RequiredArgsConstructor
public class RevenueDeliveryPlanService {

    private final RevenueDeliveryPlanMapper planMapper;
    private final RevenueCostEntryMapper costEntryMapper;
    private final RevenueMonthService monthService;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;

    public List<RevenueDeliveryPlan> list(Integer year, Long businessLineId, Long projectId) {
        LambdaQueryWrapper<RevenueDeliveryPlan> wrapper = new LambdaQueryWrapper<RevenueDeliveryPlan>()
                .eq(businessLineId != null, RevenueDeliveryPlan::getBusinessLineId, businessLineId)
                .eq(projectId != null, RevenueDeliveryPlan::getProjectId, projectId)
                .likeRight(year != null, RevenueDeliveryPlan::getYearMonth, year + "-")
                .orderByAsc(RevenueDeliveryPlan::getYearMonth)
                .orderByAsc(RevenueDeliveryPlan::getId);
        return planMapper.selectList(wrapper);
    }

    public RevenueDeliveryPlan create(RevenueDeliveryPlan request, Long userId) {
        validate(request);
        request.setId(null);
        request.setCreatedBy(userId);
        applyUnitPrice(request);
        planMapper.insert(request);
        return request;
    }

    public List<RevenueDeliveryPlan> createBatch(RevenuePlanBatchRequest batch, Long userId) {
        if (batch.getRows() == null || batch.getRows().isEmpty()) {
            throw new IllegalArgumentException("预估交付计划不能为空");
        }
        if (batch.getBusinessLineId() == null) {
            throw new IllegalArgumentException("业务线不能为空");
        }
        List<RevenueDeliveryPlan> created = new ArrayList<>();
        for (RevenuePlanBatchRequest.Row row : batch.getRows()) {
            validate(row.getYearMonth(), batch.getBusinessLineId(), batch.getProjectId());
            if (batch.getYear() != null && row.getYearMonth() != null
                    && !row.getYearMonth().startsWith(batch.getYear() + "-")) {
                throw new IllegalArgumentException("月份 " + row.getYearMonth() + " 不属于年份 " + batch.getYear());
            }
            RevenueDeliveryPlan plan = new RevenueDeliveryPlan();
            plan.setYearMonth(row.getYearMonth());
            plan.setBusinessLineId(batch.getBusinessLineId());
            plan.setProjectId(batch.getProjectId());
            plan.setAmountYuan(row.getAmountYuan());
            plan.setPersonMonths(row.getPersonMonths());
            plan.setNote(row.getNote());
            plan.setCreatedBy(userId);
            applyUnitPrice(plan);
            planMapper.insert(plan);
            created.add(plan);
        }
        return created;
    }

    public RevenueDeliveryPlan update(Long id, RevenueDeliveryPlan request) {
        RevenueDeliveryPlan existing = require(id);
        RevenueDeliveryPlan merged = merge(existing, request);
        validate(merged);
        // 单价快照：沿用录入时快照；若当时无历史（快照为空）则更新时按当下历史补算
        merged.setUnitPriceSnapshot(existing.getUnitPriceSnapshot());
        applyUnitPrice(merged, true);
        merged.setCreatedBy(existing.getCreatedBy());
        merged.setCreatedAt(existing.getCreatedAt());
        merged.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(merged);
        return planMapper.selectById(id);
    }

    public void delete(Long id) {
        if (planMapper.selectById(id) == null) {
            throw new IllegalArgumentException("预估交付计划不存在");
        }
        planMapper.deleteById(id);
    }

    /**
     * 历史完结累计单价（元/人月）：
     * projectId 非空 → 该项目全部完结月 cost 合计/人月；
     * 为空 → 该业务线 project 类完结成本/人月（业务线聚合行）。
     */
    public BigDecimal historicalUnitPrice(Long businessLineId, Long projectId) {
        LambdaQueryWrapper<RevenueCostEntry> wrapper = new LambdaQueryWrapper<RevenueCostEntry>()
                .eq(RevenueCostEntry::getPending, 0);
        if (projectId != null) {
            wrapper.eq(RevenueCostEntry::getProjectId, projectId);
        } else {
            wrapper.eq(RevenueCostEntry::getBusinessLineId, businessLineId)
                    .eq(RevenueCostEntry::getWorkType, "project");
        }
        List<RevenueCostEntry> entries = costEntryMapper.selectList(wrapper);
        BigDecimal hours = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (RevenueCostEntry entry : entries) {
            if (monthService.isClosed(entry.getYearMonth())) {
                hours = hours.add(entry.getHours());
                cost = cost.add(entry.getCostAmount());
            }
        }
        return hours.compareTo(BigDecimal.ZERO) > 0 ? cost.divide(hours, 2, RoundingMode.HALF_UP) : null;
    }

    private void applyUnitPrice(RevenueDeliveryPlan plan) {
        applyUnitPrice(plan, false);
    }

    private void applyUnitPrice(RevenueDeliveryPlan plan, boolean reuseExistingSnapshot) {
        BigDecimal snapshot;
        if (reuseExistingSnapshot && plan.getUnitPriceSnapshot() != null) {
            snapshot = plan.getUnitPriceSnapshot();
        } else {
            snapshot = historicalUnitPrice(plan.getBusinessLineId(), plan.getProjectId());
        }
        plan.setUnitPriceSnapshot(snapshot);
        if (plan.getPersonMonths() == null || snapshot == null) {
            plan.setLaborCostYuan(null);
        } else {
            plan.setLaborCostYuan(plan.getPersonMonths().multiply(snapshot)
                    .setScale(2, RoundingMode.HALF_UP));
        }
    }

    private RevenueDeliveryPlan merge(RevenueDeliveryPlan existing, RevenueDeliveryPlan request) {
        RevenueDeliveryPlan merged = new RevenueDeliveryPlan();
        merged.setId(existing.getId());
        merged.setYearMonth(existing.getYearMonth());
        merged.setBusinessLineId(existing.getBusinessLineId());
        merged.setProjectId(existing.getProjectId());
        merged.setUnitPriceSnapshot(existing.getUnitPriceSnapshot());
        merged.setAmountYuan(request.getAmountYuan() == null ? existing.getAmountYuan() : request.getAmountYuan());
        merged.setPersonMonths(request.getPersonMonths() == null ? existing.getPersonMonths() : request.getPersonMonths());
        merged.setNote(request.getNote() == null ? existing.getNote() : request.getNote());
        return merged;
    }

    private void validate(RevenueDeliveryPlan plan) {
        validate(plan.getYearMonth(), plan.getBusinessLineId(), plan.getProjectId());
        if (plan.getAmountYuan() == null || plan.getAmountYuan().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("预估交付金额不能为空且不能为负");
        }
        if (plan.getPersonMonths() != null && plan.getPersonMonths().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("预估人月不能为负");
        }
    }

    private void validate(String yearMonth, Long businessLineId, Long projectId) {
        requireMonth(yearMonth);
        if (businessLineId == null) {
            throw new IllegalArgumentException("业务线不能为空");
        }
        BusinessLine line = businessLineMapper.selectById(businessLineId);
        if (line == null) {
            throw new IllegalArgumentException("业务线不存在");
        }
        if (projectId == null) {
            String mode = StringUtils.hasText(line.getRevenueMode()) ? line.getRevenueMode() : "full";
            if ("full".equals(mode)) {
                throw new IllegalArgumentException("该业务线无业务线级聚合行，预估交付请挂到具体项目");
            }
        } else {
            Project project = projectMapper.selectById(projectId);
            if (project == null) {
                throw new IllegalArgumentException("项目不存在");
            }
            if (!businessLineId.equals(project.getBusinessLineId())) {
                throw new IllegalArgumentException("项目不属于该业务线");
            }
        }
    }

    private RevenueDeliveryPlan require(Long id) {
        RevenueDeliveryPlan plan = planMapper.selectById(id);
        if (plan == null) {
            throw new IllegalArgumentException("预估交付计划不存在");
        }
        return plan;
    }

    static void requireMonth(String yearMonth) {
        if (!StringUtils.hasText(yearMonth)) {
            throw new IllegalArgumentException("月份不能为空");
        }
        try {
            YearMonth.parse(yearMonth);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("月份格式应为 yyyy-MM: " + yearMonth);
        }
    }
}
