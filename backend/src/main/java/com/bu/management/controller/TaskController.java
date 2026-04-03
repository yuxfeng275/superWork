package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.CreateTaskDTO;
import com.bu.management.dto.UpdateTaskDTO;
import com.bu.management.entity.Task;
import com.bu.management.service.TaskService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 任务控制器
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Tag(name = "任务管理", description = "任务管理接口")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    /**
     * 创建任务
     */
    @Operation(summary = "创建任务", description = "创建任务")
    @PostMapping
    public Result<Task> createTask(@RequestBody CreateTaskDTO dto) {
        Task task = taskService.createTask(dto);
        return Result.success(task);
    }

    /**
     * 更新任务
     */
    @Operation(summary = "更新任务", description = "更新任务")
    @PutMapping("/{id}")
    public Result<Task> updateTask(
            @Parameter(description = "任务ID") @PathVariable Long id,
            @RequestBody UpdateTaskDTO dto) {
        Task task = taskService.updateTask(id, dto);
        return Result.success(task);
    }

    /**
     * 查询任务详情
     */
    @Operation(summary = "查询任务详情", description = "根据任务ID查询详情")
    @GetMapping("/{id}")
    public Result<Task> getTask(@Parameter(description = "任务ID") @PathVariable Long id) {
        Task task = taskService.getTaskById(id);
        return Result.success(task);
    }

    /**
     * 查询需求的任务列表
     */
    @Operation(summary = "查询需求的任务列表", description = "根据需求ID查询所有任务")
    @GetMapping("/requirement/{requirementId}")
    public Result<List<Task>> getTasksByRequirement(
            @Parameter(description = "需求ID") @PathVariable Long requirementId) {
        List<Task> tasks = taskService.getTasksByRequirementId(requirementId);
        return Result.success(tasks);
    }

    /**
     * 分页查询任务
     */
    @Operation(summary = "分页查询任务", description = "分页查询任务，支持按需求ID、负责人、状态、优先级筛选")
    @GetMapping
    public Result<Page<Task>> getTasksPage(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "需求ID") @RequestParam(required = false) Long requirementId,
            @Parameter(description = "负责人ID") @RequestParam(required = false) Long assigneeId,
            @Parameter(description = "状态") @RequestParam(required = false) String status,
            @Parameter(description = "优先级") @RequestParam(required = false) String priority) {
        Page<Task> result = taskService.getTasksPage(page, size, requirementId, assigneeId, status, priority);
        return Result.success(result);
    }

    /**
     * 删除任务
     */
    @Operation(summary = "删除任务", description = "删除任务")
    @DeleteMapping("/{id}")
    public Result<Void> deleteTask(@Parameter(description = "任务ID") @PathVariable Long id) {
        taskService.deleteTask(id);
        return Result.success();
    }
}
