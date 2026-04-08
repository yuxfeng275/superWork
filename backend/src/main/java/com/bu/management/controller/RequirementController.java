package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.RequirementRequest;
import com.bu.management.entity.Requirement;
import com.bu.management.service.RequirementService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
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

    /**
     * 创建需求
     */
    @Operation(summary = "创建需求", description = "创建新的需求（项目需求或产品需求）")
    @PostMapping
    @RequirePermission({"requirement:create"})
    public Result<Requirement> create(@Valid @RequestBody RequirementRequest request,
                                      Authentication authentication) {
        // 从认证信息中获取当前用户ID（简化处理，实际应从 UserDetails 中获取）
        Long creatorId = 1L; // TODO: 从 authentication 中获取真实用户ID
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
            Authentication authentication,
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
