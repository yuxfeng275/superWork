package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.WorkflowConfigRequest;
import com.bu.management.entity.WorkflowConfig;
import com.bu.management.service.WorkflowConfigService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow-configs")
@RequiredArgsConstructor
@Tag(name = "工作流配置", description = "工作流配置管理接口")
public class WorkflowConfigController {

    private final WorkflowConfigService workflowConfigService;

    @Operation(summary = "获取所有工作流配置", description = "获取后台配置页使用的全部工作流配置")
    @GetMapping
    @RequirePermission({"system:workflow:list"})
    public Result<List<WorkflowConfig>> getAllConfigs() {
        return Result.success(workflowConfigService.getAllConfigs());
    }

    @Operation(summary = "获取工作流配置详情", description = "根据ID获取工作流配置详情")
    @GetMapping("/{id}")
    @RequirePermission({"system:workflow:list"})
    public Result<WorkflowConfig> getConfigById(@PathVariable Long id) {
        return Result.success(workflowConfigService.getById(id));
    }

    @Operation(summary = "创建工作流配置", description = "创建新的工作流配置规则")
    @PostMapping
    @RequirePermission({"system:workflow:edit"})
    public Result<WorkflowConfig> create(
            @Valid @RequestBody WorkflowConfigRequest request) {
        return Result.success("创建成功", workflowConfigService.create(request));
    }

    @Operation(summary = "更新工作流配置", description = "更新指定工作流配置规则")
    @PutMapping("/{id}")
    @RequirePermission({"system:workflow:edit"})
    public Result<WorkflowConfig> update(
            @Parameter(description = "工作流配置ID") @PathVariable Long id,
            @Valid @RequestBody WorkflowConfigRequest request) {
        return Result.success("更新成功", workflowConfigService.update(id, request));
    }

    @Operation(summary = "删除工作流配置", description = "删除指定工作流配置规则")
    @DeleteMapping("/{id}")
    @RequirePermission({"system:workflow:edit"})
    public Result<Void> delete(@Parameter(description = "工作流配置ID") @PathVariable Long id) {
        workflowConfigService.delete(id);
        return Result.success("删除成功", null);
    }

    @Operation(summary = "获取指定需求类型的工作流配置", description = "获取指定需求类型的所有工作流配置")
    @GetMapping("/type/{requirementType}")
    @RequirePermission({"system:workflow:list"})
    public Result<List<WorkflowConfig>> getConfigsByType(
            @Parameter(description = "需求类型") @PathVariable String requirementType) {
        return Result.success(workflowConfigService.getConfigsByType(requirementType));
    }

    @Operation(summary = "获取可流转的目标状态", description = "根据当前状态和需求类型获取可流转的目标状态列表")
    @GetMapping("/next-statuses")
    @RequirePermission({"system:workflow:list"})
    public Result<List<String>> getNextStatuses(
            @Parameter(description = "需求类型") @RequestParam String requirementType,
            @Parameter(description = "当前状态") @RequestParam String currentStatus) {
        return Result.success(workflowConfigService.getNextStatuses(requirementType, currentStatus));
    }
}
