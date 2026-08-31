package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueEstimateEntry;
import com.bu.management.entity.RevenueSalesProject;
import com.bu.management.entity.RevenueWorklogEntry;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueEstimateEntryMapper;
import com.bu.management.mapper.RevenueSalesProjectMapper;
import com.bu.management.mapper.RevenueWorklogEntryMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 营收维护：预估明细 CRUD（金额 = 人月 × 历史完结人均成本快照）、
 * 待映射处理、销售项目与商机的手动关联。
 */
@Service
@RequiredArgsConstructor
public class RevenueAdminService {

    private final RevenueEstimateEntryMapper estimateEntryMapper;
    private final RevenueWorklogEntryMapper worklogEntryMapper;
    private final RevenueCostEntryMapper costEntryMapper;
    private final RevenueSalesProjectMapper salesProjectMapper;
    private final SalesOpportunityMapper opportunityMapper;
    private final RevenueMonthService monthService;
    private final RevenueMappingResolver mappingResolver;

    /** 历史完结人均成本：该项目全部完结月的成本合计 ÷ 人月合计（元/人月） */
    public BigDecimal historicalUnitPrice(Long projectId) {
        if (projectId == null) {
            return null;
        }
        List<RevenueCostEntry> entries = costEntryMapper.selectList(new LambdaQueryWrapper<RevenueCostEntry>()
                .eq(RevenueCostEntry::getProjectId, projectId)
                .eq(RevenueCostEntry::getPending, 0));
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

    public List<RevenueEstimateEntry> listEstimates(String yearMonth) {
        return estimateEntryMapper.selectList(new LambdaQueryWrapper<RevenueEstimateEntry>()
                .eq(StringUtils.hasText(yearMonth), RevenueEstimateEntry::getYearMonth, yearMonth)
                .orderByAsc(RevenueEstimateEntry::getYearMonth)
                .orderByAsc(RevenueEstimateEntry::getId));
    }

    public RevenueEstimateEntry createEstimate(RevenueEstimateEntry request, Long userId) {
        monthService.assertNotClosed(request.getYearMonth());
        validateEstimate(request);
        applyUnitPrice(request);
        request.setId(null);
        request.setCreatedBy(userId);
        estimateEntryMapper.insert(request);
        return request;
    }

    public RevenueEstimateEntry updateEstimate(Long id, RevenueEstimateEntry request) {
        RevenueEstimateEntry existing = requireEstimate(id);
        monthService.assertNotClosed(existing.getYearMonth());
        request.setId(id);
        request.setYearMonth(existing.getYearMonth());
        validateEstimate(request);
        applyUnitPrice(request);
        estimateEntryMapper.updateById(request);
        return estimateEntryMapper.selectById(id);
    }

    public void deleteEstimate(Long id) {
        RevenueEstimateEntry existing = requireEstimate(id);
        monthService.assertNotClosed(existing.getYearMonth());
        estimateEntryMapper.deleteById(id);
    }

    private RevenueEstimateEntry requireEstimate(Long id) {
        RevenueEstimateEntry existing = estimateEntryMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("预估明细不存在");
        }
        return existing;
    }

    private void validateEstimate(RevenueEstimateEntry request) {
        if (!StringUtils.hasText(request.getYearMonth()) || request.getBusinessLineId() == null
                || !StringUtils.hasText(request.getDescription()) || request.getPersonMonths() == null
                || request.getPersonMonths().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("月份、业务线、描述和人月不能为空，人月必须大于 0");
        }
        if (!StringUtils.hasText(request.getWorkType())) {
            request.setWorkType("project");
        }
    }

    private void applyUnitPrice(RevenueEstimateEntry request) {
        BigDecimal unitPrice = historicalUnitPrice(request.getProjectId());
        request.setUnitPrice(unitPrice);
        request.setAmount(unitPrice == null ? null
                : request.getPersonMonths().multiply(unitPrice).setScale(2, RoundingMode.HALF_UP));
    }

    /** 待映射清单：工时 + 成本两张表的 pending 行 */
    public Map<String, Object> listPending() {
        Map<String, Object> result = new HashMap<>();
        result.put("worklog", worklogEntryMapper.selectList(new LambdaQueryWrapper<RevenueWorklogEntry>()
                .eq(RevenueWorklogEntry::getPending, 1)
                .orderByDesc(RevenueWorklogEntry::getYearMonth)));
        result.put("cost", costEntryMapper.selectList(new LambdaQueryWrapper<RevenueCostEntry>()
                .eq(RevenueCostEntry::getPending, 1)
                .orderByDesc(RevenueCostEntry::getYearMonth)));
        return result;
    }

    /** 人工指定归属：业务线必选；projectId 为空视为业务线级（项目集/商机集合/其他按 salesKind 保持） */
    public void resolvePending(String type, Long id, Long businessLineId, Long projectId) {
        if (businessLineId == null) {
            throw new IllegalArgumentException("请选择归属业务线");
        }
        if ("worklog".equals(type)) {
            RevenueWorklogEntry entry = worklogEntryMapper.selectById(id);
            if (entry == null) {
                throw new IllegalArgumentException("工时明细不存在");
            }
            entry.setBusinessLineId(businessLineId);
            if ("project".equals(entry.getWorkType()) && !"pool".equals(entry.getSalesKind())) {
                entry.setProjectId(projectId);
            }
            if ("specific".equals(entry.getSalesKind())) {
                RevenueSalesProject salesProject = mappingResolver.registerSalesProject(businessLineId,
                        entry.getProjectNameRaw().replaceAll("【.*】$", ""));
                entry.setSalesProjectId(salesProject == null ? null : salesProject.getId());
            }
            entry.setPending(0);
            worklogEntryMapper.updateById(entry);
            return;
        }
        if ("cost".equals(type)) {
            RevenueCostEntry entry = costEntryMapper.selectById(id);
            if (entry == null) {
                throw new IllegalArgumentException("成本明细不存在");
            }
            entry.setBusinessLineId(businessLineId);
            if ("project".equals(entry.getWorkType())) {
                entry.setProjectId(projectId);
            }
            entry.setPending(0);
            costEntryMapper.updateById(entry);
            return;
        }
        throw new IllegalArgumentException("未知明细类型: " + type);
    }

    public List<Map<String, Object>> listSalesProjects() {
        Map<Long, String> opportunityNames = new HashMap<>();
        opportunityMapper.selectList(null).forEach(item -> opportunityNames.put(item.getId(), item.getName()));
        return salesProjectMapper.selectList(null).stream().map(item -> {
            Map<String, Object> view = new HashMap<>();
            view.put("id", item.getId());
            view.put("businessLineId", item.getBusinessLineId());
            view.put("name", item.getName());
            view.put("opportunityId", item.getOpportunityId());
            view.put("opportunityName", item.getOpportunityId() == null ? null : opportunityNames.get(item.getOpportunityId()));
            return view;
        }).toList();
    }

    public RevenueSalesProject bindOpportunity(Long id, Long opportunityId) {
        RevenueSalesProject salesProject = salesProjectMapper.selectById(id);
        if (salesProject == null) {
            throw new IllegalArgumentException("销售项目不存在");
        }
        if (opportunityId != null) {
            SalesOpportunity opportunity = opportunityMapper.selectById(opportunityId);
            if (opportunity == null) {
                throw new IllegalArgumentException("商机不存在");
            }
        }
        salesProject.setOpportunityId(opportunityId);
        salesProject.setUpdatedAt(LocalDateTime.now());
        salesProjectMapper.updateById(salesProject);
        return salesProject;
    }

    /**
     * 手工补录工时明细（完结月补录/修正通道；导入仍要求未完结）。
     * batch_id 为 NULL，created_by 记录录入人；商机集合行自动打标签。
     */
    public RevenueWorklogEntry createWorklogEntry(RevenueWorklogEntry request, Long userId) {
        validateWorklogEntry(request);
        request.setId(null);
        request.setBatchId(null);
        request.setPending(0);
        request.setCreatedBy(userId);
        request.setCreatedAt(LocalDateTime.now());
        if ("pool".equals(request.getSalesKind())) {
            request.setTags(mappingResolver.tagWorkNote(request.getWorkNote()));
        }
        worklogEntryMapper.insert(request);
        return request;
    }

    public RevenueWorklogEntry updateWorklogEntry(Long id, RevenueWorklogEntry request) {
        RevenueWorklogEntry existing = worklogEntryMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("工时明细不存在");
        }
        validateWorklogEntry(request);
        request.setId(id);
        request.setBatchId(existing.getBatchId());
        request.setPending(existing.getPending());
        request.setCreatedBy(existing.getCreatedBy());
        request.setCreatedAt(existing.getCreatedAt());
        if ("pool".equals(request.getSalesKind())) {
            request.setTags(mappingResolver.tagWorkNote(request.getWorkNote()));
        }
        worklogEntryMapper.updateById(request);
        return worklogEntryMapper.selectById(id);
    }

    public void deleteWorklogEntry(Long id) {
        if (worklogEntryMapper.selectById(id) == null) {
            throw new IllegalArgumentException("工时明细不存在");
        }
        worklogEntryMapper.deleteById(id);
    }

    public RevenueCostEntry createCostEntry(RevenueCostEntry request, Long userId) {
        validateCostEntry(request);
        request.setId(null);
        request.setBatchId(null);
        request.setPending(0);
        request.setCreatedBy(userId);
        request.setCreatedAt(LocalDateTime.now());
        costEntryMapper.insert(request);
        return request;
    }

    public RevenueCostEntry updateCostEntry(Long id, RevenueCostEntry request) {
        RevenueCostEntry existing = costEntryMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("成本明细不存在");
        }
        validateCostEntry(request);
        request.setId(id);
        request.setBatchId(existing.getBatchId());
        request.setPending(existing.getPending());
        request.setCreatedBy(existing.getCreatedBy());
        request.setCreatedAt(existing.getCreatedAt());
        costEntryMapper.updateById(request);
        return costEntryMapper.selectById(id);
    }

    public void deleteCostEntry(Long id) {
        if (costEntryMapper.selectById(id) == null) {
            throw new IllegalArgumentException("成本明细不存在");
        }
        costEntryMapper.deleteById(id);
    }

    private void validateWorklogEntry(RevenueWorklogEntry request) {
        if (!StringUtils.hasText(request.getYearMonth()) || request.getBusinessLineId() == null
                || request.getHours() == null || request.getHours().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("月份、业务线和人月不能为空，人月必须大于 0");
        }
        if (!StringUtils.hasText(request.getWorkType())) {
            request.setWorkType("project");
        }
        if (!StringUtils.hasText(request.getProjectNameRaw())) {
            request.setProjectNameRaw("手工补录");
        }
    }

    private void validateCostEntry(RevenueCostEntry request) {
        if (!StringUtils.hasText(request.getYearMonth()) || request.getBusinessLineId() == null
                || request.getHours() == null || request.getHours().compareTo(BigDecimal.ZERO) < 0
                || request.getCostAmount() == null || request.getCostAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("月份、业务线、人月和成本不能为空，且金额不能为负");
        }
        if (!StringUtils.hasText(request.getWorkType())) {
            request.setWorkType("project");
        }
        if (!StringUtils.hasText(request.getProjectNameRaw())) {
            request.setProjectNameRaw("手工补录");
        }
    }

    public List<SalesOpportunity> listOpportunityOptions() {
        return opportunityMapper.selectList(new LambdaQueryWrapper<SalesOpportunity>()
                .orderByDesc(SalesOpportunity::getId));
    }
}
