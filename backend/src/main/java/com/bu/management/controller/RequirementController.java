package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.RequirementRequest;
import com.bu.management.dto.RequirementStageActionRequest;
import com.bu.management.entity.Requirement;
import com.bu.management.service.RequirementOverviewService;
import com.bu.management.service.RequirementService;
import com.bu.management.vo.Result;
import com.bu.management.vo.WorkItemOverviewQuery;
import com.bu.management.vo.WorkItemOverviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 需求控制器
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Tag(name = "需求管理", description = "需求的增删改查接口")
@RestController
@RequestMapping("/api/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private final RequirementService requirementService;
    private final RequirementOverviewService requirementOverviewService;

    /**
     * 创建需求
     */
    @Operation(summary = "创建需求", description = "创建新的需求（项目需求或产品需求）")
    @PostMapping
    @RequirePermission({"requirement:create"})
    public Result<Requirement> create(@Valid @RequestBody RequirementRequest request,
                                      @RequestAttribute("userId") Long creatorId) {
        Requirement requirement = requirementService.create(request, creatorId);
        return Result.success("创建成功", requirement);
    }

    /**
     * 更新需求
     */
    @Operation(summary = "更新需求", description = "更新需求信息")
    @PutMapping("/{id}")
    @RequirePermission({"requirement:edit"})
    public Result<Requirement> update(
            @Parameter(description = "需求ID") @PathVariable Long id,
            @Valid @RequestBody RequirementRequest request) {
        Requirement requirement = requirementService.update(id, request);
        return Result.success("更新成功", requirement);
    }

    @Operation(summary = "执行需求阶段动作", description = "执行需求阶段管理中的简单状态切换动作")
    @PostMapping("/{id}/stage-actions")
    @RequirePermission({"requirement:edit"})
    public Result<Requirement> executeStageAction(
            @Parameter(description = "需求ID") @PathVariable Long id,
            @Valid @RequestBody RequirementStageActionRequest request) {
        Requirement requirement = requirementService.executeStageAction(id, request.getAction());
        return Result.success("操作成功", requirement);
    }

    /**
     * 删除需求
     */
    @Operation(summary = "删除需求", description = "删除指定需求（仅待评估状态可删除）")
    @DeleteMapping("/{id}")
    @RequirePermission({"requirement:delete"})
    public Result<Void> delete(@Parameter(description = "需求ID") @PathVariable Long id) {
        requirementService.delete(id);
        return Result.success("删除成功", null);
    }

    /**
     * 获取需求详情
     */
    @Operation(summary = "获取需求详情", description = "根据ID获取需求详情")
    @GetMapping("/{id}")
    @RequirePermission({"requirement:list"})
    public Result<Requirement> getById(@Parameter(description = "需求ID") @PathVariable Long id) {
        Requirement requirement = requirementService.getById(id);
        return Result.success(requirement);
    }

    @Operation(summary = "查询统一需求概览", description = "合并本地需求与云效只读需求")
    @GetMapping("/overview")
    @RequirePermission({"requirement:list"})
    public Result<WorkItemOverviewResponse> overview(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long businessLineId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) String dataSource,
            @RequestParam(required = false) String normalizedStatus,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        WorkItemOverviewQuery query = new WorkItemOverviewQuery();
        query.setPage(page);
        query.setSize(size);
        query.setBusinessLineId(businessLineId);
        query.setProjectId(projectId);
        query.setAssigneeId(assigneeId);
        query.setDataSource(dataSource);
        query.setNormalizedStatus(normalizedStatus);
        query.setType(type);
        query.setPriority(priority);
        query.setKeyword(keyword);
        return Result.success(requirementOverviewService.getOverview(
                (Long) request.getAttribute("userId"),
                (String) request.getAttribute("role"), query));
    }

    /**
     * 分页查询需求列表
     */
    @Operation(summary = "分页查询需求", description = "分页查询需求列表，支持多条件筛选")
    @GetMapping
    @RequirePermission({"requirement:list"})
    public Result<Page<Requirement>> list(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "业务线ID") @RequestParam(required = false) Long businessLineId,
            @Parameter(description = "项目ID") @RequestParam(required = false) Long projectId,
            @Parameter(description = "类型：项目需求/产品需求") @RequestParam(required = false) String type,
            @Parameter(description = "状态") @RequestParam(required = false) String status,
            @Parameter(description = "优先级：高/中/低") @RequestParam(required = false) String priority,
            @Parameter(description = "标题（模糊查询）") @RequestParam(required = false) String title,
            HttpServletRequest request) {
        // 获取当前用户ID和角色
        Long userId = (Long) request.getAttribute("userId");
        String role = (String) request.getAttribute("role");
        // 使用带权限过滤的列表查询
        Page<Requirement> result = requirementService.listWithPermission(userId, role, page, size,
                businessLineId, projectId, type, status, priority, title);
        return Result.success(result);
    }
}
