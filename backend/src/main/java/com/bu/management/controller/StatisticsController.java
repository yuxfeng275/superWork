package com.bu.management.controller;

import com.bu.management.service.StatisticsService;
import com.bu.management.vo.RequirementStatistics;
import com.bu.management.vo.Result;
import com.bu.management.vo.TaskStatistics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 统计控制器
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Tag(name = "数据统计", description = "数据统计接口")
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    /**
     * 获取需求统计
     */
    @Operation(summary = "获取需求统计", description = "获取需求统计数据，支持按项目ID、业务线ID筛选")
    @GetMapping("/requirements")
    public Result<RequirementStatistics> getRequirementStatistics(
            @Parameter(description = "项目ID") @RequestParam(required = false) Long projectId,
            @Parameter(description = "业务线ID") @RequestParam(required = false) Long businessLineId) {
        RequirementStatistics stats = statisticsService.getRequirementStatistics(projectId, businessLineId);
        return Result.success(stats);
    }

    /**
     * 获取任务统计
     */
    @Operation(summary = "获取任务统计", description = "获取任务统计数据，支持按需求ID、负责人ID筛选")
    @GetMapping("/tasks")
    public Result<TaskStatistics> getTaskStatistics(
            @Parameter(description = "需求ID") @RequestParam(required = false) Long requirementId,
            @Parameter(description = "负责人ID") @RequestParam(required = false) Long assigneeId) {
        TaskStatistics stats = statisticsService.getTaskStatistics(requirementId, assigneeId);
        return Result.success(stats);
    }
}
