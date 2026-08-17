package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.CreateDesignWorkLogDTO;
import com.bu.management.dto.UpdateDesignWorkLogDTO;
import com.bu.management.entity.DesignWorkLog;
import com.bu.management.service.DesignWorkLogService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 设计工作记录控制器
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Tag(name = "设计工作记录", description = "设计工作记录管理接口")
@RestController
@RequestMapping("/api/design-work-logs")
@RequiredArgsConstructor
@RequirePermission({"requirement:list"})
public class DesignWorkLogController {

    private final DesignWorkLogService workLogService;

    /**
     * 创建设计工作记录
     */
    @Operation(summary = "创建设计工作记录", description = "创建设计工作记录")
    @PostMapping
    @RequirePermission({"requirement:edit"})
    public Result<DesignWorkLog> createWorkLog(@RequestBody CreateDesignWorkLogDTO dto) {
        DesignWorkLog workLog = workLogService.createWorkLog(dto);
        return Result.success(workLog);
    }

    /**
     * 更新设计工作记录
     */
    @Operation(summary = "更新设计工作记录", description = "更新设计工作记录")
    @PutMapping("/{id}")
    @RequirePermission({"requirement:edit"})
    public Result<DesignWorkLog> updateWorkLog(
            @Parameter(description = "工作记录ID") @PathVariable Long id,
            @RequestBody UpdateDesignWorkLogDTO dto) {
        DesignWorkLog workLog = workLogService.updateWorkLog(id, dto);
        return Result.success(workLog);
    }

    /**
     * 查询需求的设计工作记录列表
     */
    @Operation(summary = "查询需求的设计工作记录", description = "根据需求ID查询所有设计工作记录")
    @GetMapping("/requirement/{requirementId}")
    public Result<List<DesignWorkLog>> getWorkLogsByRequirement(
            @Parameter(description = "需求ID") @PathVariable Long requirementId) {
        List<DesignWorkLog> workLogs = workLogService.getWorkLogsByRequirementId(requirementId);
        return Result.success(workLogs);
    }

    /**
     * 分页查询设计工作记录
     */
    @Operation(summary = "分页查询设计工作记录", description = "分页查询设计工作记录，支持按需求ID、工作类型、状态筛选")
    @GetMapping
    public Result<Page<DesignWorkLog>> getWorkLogsPage(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "需求ID") @RequestParam(required = false) Long requirementId,
            @Parameter(description = "工作类型") @RequestParam(required = false) String workType,
            @Parameter(description = "状态") @RequestParam(required = false) String status) {
        Page<DesignWorkLog> result = workLogService.getWorkLogsPage(page, size, requirementId, workType, status);
        return Result.success(result);
    }

    /**
     * 删除设计工作记录
     */
    @Operation(summary = "删除设计工作记录", description = "删除设计工作记录")
    @DeleteMapping("/{id}")
    @RequirePermission({"requirement:edit"})
    public Result<Void> deleteWorkLog(@Parameter(description = "工作记录ID") @PathVariable Long id) {
        workLogService.deleteWorkLog(id);
        return Result.success();
    }
}
