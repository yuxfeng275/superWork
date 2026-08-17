package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.CreateWorkLogDTO;
import com.bu.management.dto.UpdateWorkLogDTO;
import com.bu.management.entity.WorkLog;
import com.bu.management.service.WorkLogService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 工时记录控制器
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Tag(name = "工时记录", description = "工时记录管理接口")
@RestController
@RequestMapping("/api/work-logs")
@RequiredArgsConstructor
@RequirePermission({"task:list"})
public class WorkLogController {

    private final WorkLogService workLogService;

    /**
     * 创建工时记录
     */
    @Operation(summary = "创建工时记录", description = "创建工时记录，自动汇总更新任务实际工时")
    @PostMapping
    @RequirePermission({"task:edit"})
    public Result<WorkLog> createWorkLog(@RequestBody CreateWorkLogDTO dto) {
        WorkLog workLog = workLogService.createWorkLog(dto);
        return Result.success(workLog);
    }

    /**
     * 更新工时记录
     */
    @Operation(summary = "更新工时记录", description = "更新工时记录，自动汇总更新任务实际工时")
    @PutMapping("/{id}")
    @RequirePermission({"task:edit"})
    public Result<WorkLog> updateWorkLog(
            @Parameter(description = "工时记录ID") @PathVariable Long id,
            @RequestBody UpdateWorkLogDTO dto) {
        WorkLog workLog = workLogService.updateWorkLog(id, dto);
        return Result.success(workLog);
    }

    /**
     * 查询工时记录详情
     */
    @Operation(summary = "查询工时记录详情", description = "根据工时记录ID查询详情")
    @GetMapping("/{id}")
    public Result<WorkLog> getWorkLog(@Parameter(description = "工时记录ID") @PathVariable Long id) {
        WorkLog workLog = workLogService.getWorkLogById(id);
        return Result.success(workLog);
    }

    /**
     * 查询任务的工时记录列表
     */
    @Operation(summary = "查询任务的工时记录", description = "根据任务ID查询所有工时记录")
    @GetMapping("/task/{taskId}")
    public Result<List<WorkLog>> getWorkLogsByTask(
            @Parameter(description = "任务ID") @PathVariable Long taskId) {
        List<WorkLog> workLogs = workLogService.getWorkLogsByTaskId(taskId);
        return Result.success(workLogs);
    }

    /**
     * 分页查询工时记录
     */
    @Operation(summary = "分页查询工时记录", description = "分页查询工时记录，支持按任务ID、用户ID、日期范围筛选")
    @GetMapping
    public Result<Page<WorkLog>> getWorkLogsPage(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "任务ID") @RequestParam(required = false) Long taskId,
            @Parameter(description = "用户ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "开始日期") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Page<WorkLog> result = workLogService.getWorkLogsPage(page, size, taskId, userId, startDate, endDate);
        return Result.success(result);
    }

    /**
     * 删除工时记录
     */
    @Operation(summary = "删除工时记录", description = "删除工时记录，自动汇总更新任务实际工时")
    @DeleteMapping("/{id}")
    @RequirePermission({"task:edit"})
    public Result<Void> deleteWorkLog(@Parameter(description = "工时记录ID") @PathVariable Long id) {
        workLogService.deleteWorkLog(id);
        return Result.success();
    }
}
