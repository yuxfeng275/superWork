package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.Task;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.vo.RequirementStatistics;
import com.bu.management.vo.TaskStatistics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 统计服务
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final RequirementMapper requirementMapper;
    private final TaskMapper taskMapper;

    /**
     * 获取需求统计
     */
    public RequirementStatistics getRequirementStatistics(Long projectId, Long businessLineId) {
        LambdaQueryWrapper<Requirement> wrapper = new LambdaQueryWrapper<>();

        if (projectId != null) {
            wrapper.eq(Requirement::getProjectId, projectId);
        }
        if (businessLineId != null) {
            wrapper.eq(Requirement::getBusinessLineId, businessLineId);
        }

        List<Requirement> requirements = requirementMapper.selectList(wrapper);

        RequirementStatistics stats = new RequirementStatistics();
        stats.setTotalCount((long) requirements.size());
        stats.setProjectRequirementCount(requirements.stream().filter(r -> "项目需求".equals(r.getType())).count());
        stats.setProductRequirementCount(requirements.stream().filter(r -> "产品需求".equals(r.getType())).count());
        stats.setPendingEvaluationCount(requirements.stream().filter(r -> "待评估".equals(r.getStatus())).count());
        stats.setEvaluatingCount(requirements.stream().filter(r -> "评估中".equals(r.getStatus())).count());
        stats.setPendingDesignCount(requirements.stream().filter(r -> "待设计".equals(r.getStatus())).count());
        stats.setDesigningCount(requirements.stream().filter(r -> "设计中".equals(r.getStatus())).count());
        stats.setPendingConfirmationCount(requirements.stream().filter(r -> "待确认".equals(r.getStatus())).count());
        stats.setDevelopingCount(requirements.stream().filter(r -> "开发中".equals(r.getStatus())).count());
        stats.setTestingCount(requirements.stream().filter(r -> "测试中".equals(r.getStatus())).count());
        stats.setPendingReleaseCount(requirements.stream().filter(r -> "待上线".equals(r.getStatus())).count());
        stats.setReleasedCount(requirements.stream().filter(r -> "已上线".equals(r.getStatus())).count());
        stats.setDeliveredCount(requirements.stream().filter(r -> "已交付".equals(r.getStatus())).count());
        stats.setAcceptedCount(requirements.stream().filter(r -> "已验收".equals(r.getStatus())).count());
        stats.setRejectedCount(requirements.stream().filter(r -> "已拒绝".equals(r.getStatus())).count());

        // 计算总工时（从任务汇总）
        LambdaQueryWrapper<Task> taskWrapper = new LambdaQueryWrapper<>();
        if (projectId != null || businessLineId != null) {
            List<Long> requirementIds = requirements.stream().map(Requirement::getId).toList();
            if (!requirementIds.isEmpty()) {
                taskWrapper.in(Task::getRequirementId, requirementIds);
            }
        }
        List<Task> tasks = taskMapper.selectList(taskWrapper);

        BigDecimal totalEstimated = tasks.stream()
                .map(Task::getEstimatedHours)
                .filter(h -> h != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalActual = tasks.stream()
                .map(Task::getActualHours)
                .filter(h -> h != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        stats.setTotalEstimatedHours(totalEstimated);
        stats.setTotalActualHours(totalActual);

        return stats;
    }

    /**
     * 获取任务统计
     */
    public TaskStatistics getTaskStatistics(Long requirementId, Long assigneeId) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();

        if (requirementId != null) {
            wrapper.eq(Task::getRequirementId, requirementId);
        }
        if (assigneeId != null) {
            wrapper.eq(Task::getAssigneeId, assigneeId);
        }

        List<Task> tasks = taskMapper.selectList(wrapper);

        TaskStatistics stats = new TaskStatistics();
        stats.setTotalCount((long) tasks.size());
        stats.setPendingCount(tasks.stream().filter(t -> "待开始".equals(t.getStatus())).count());
        stats.setInProgressCount(tasks.stream().filter(t -> "进行中".equals(t.getStatus())).count());
        stats.setCompletedCount(tasks.stream().filter(t -> "已完成".equals(t.getStatus())).count());
        stats.setHighPriorityCount(tasks.stream().filter(t -> "高".equals(t.getPriority())).count());
        stats.setMediumPriorityCount(tasks.stream().filter(t -> "中".equals(t.getPriority())).count());
        stats.setLowPriorityCount(tasks.stream().filter(t -> "低".equals(t.getPriority())).count());

        BigDecimal totalEstimated = tasks.stream()
                .map(Task::getEstimatedHours)
                .filter(h -> h != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalActual = tasks.stream()
                .map(Task::getActualHours)
                .filter(h -> h != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        stats.setTotalEstimatedHours(totalEstimated);
        stats.setTotalActualHours(totalActual);

        // 计算工时完成率
        if (totalEstimated.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal rate = totalActual.divide(totalEstimated, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            stats.setHoursCompletionRate(rate);
        } else {
            stats.setHoursCompletionRate(BigDecimal.ZERO);
        }

        return stats;
    }
}
