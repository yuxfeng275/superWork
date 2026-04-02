package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.ProjectRequest;
import com.bu.management.entity.Project;
import com.bu.management.service.ProjectService;
import com.bu.management.vo.ProjectTreeNode;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目控制器
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Tag(name = "项目管理", description = "项目的增删改查接口")
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /**
     * 创建项目
     */
    @Operation(summary = "创建项目", description = "创建新的项目（支持主项目和子项目）")
    @PostMapping
    public Result<Project> create(@Valid @RequestBody ProjectRequest request) {
        Project project = projectService.create(request);
        return Result.success("创建成功", project);
    }

    /**
     * 更新项目
     */
    @Operation(summary = "更新项目", description = "更新项目信息")
    @PutMapping("/{id}")
    public Result<Project> update(
            @Parameter(description = "项目ID") @PathVariable Long id,
            @Valid @RequestBody ProjectRequest request) {
        Project project = projectService.update(id, request);
        return Result.success("更新成功", project);
    }

    /**
     * 删除项目
     */
    @Operation(summary = "删除项目", description = "删除指定项目（不能有子项目）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "项目ID") @PathVariable Long id) {
        projectService.delete(id);
        return Result.success("删除成功", null);
    }

    /**
     * 获取项目详情
     */
    @Operation(summary = "获取项目详情", description = "根据ID获取项目详情")
    @GetMapping("/{id}")
    public Result<Project> getById(@Parameter(description = "项目ID") @PathVariable Long id) {
        Project project = projectService.getById(id);
        return Result.success(project);
    }

    /**
     * 分页查询项目列表
     */
    @Operation(summary = "分页查询项目", description = "分页查询项目列表，支持业务线、名称搜索和状态筛选")
    @GetMapping
    public Result<Page<Project>> list(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "业务线ID") @RequestParam(required = false) Long businessLineId,
            @Parameter(description = "项目名称（模糊查询）") @RequestParam(required = false) String name,
            @Parameter(description = "状态：1=进行中，0=已结束") @RequestParam(required = false) Integer status) {
        Page<Project> result = projectService.list(page, size, businessLineId, name, status);
        return Result.success(result);
    }

    /**
     * 获取项目树
     */
    @Operation(summary = "获取项目树", description = "获取项目树形结构（主项目-子项目）")
    @GetMapping("/tree")
    public Result<List<ProjectTreeNode>> getTree(
            @Parameter(description = "业务线ID（可选）") @RequestParam(required = false) Long businessLineId) {
        List<ProjectTreeNode> tree = projectService.getTree(businessLineId);
        return Result.success(tree);
    }
}
