package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.entity.ProjectGitRepo;
import com.bu.management.service.ProjectGitRepoService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目代码仓库绑定（HTTPS）。
 *
 * @author BU Team
 * @since 2026-05-29
 */
@Tag(name = "项目代码仓库", description = "项目绑定 HTTPS 代码仓库的查询、绑定与解绑接口")
@RestController
@RequestMapping("/api/projects/{projectId}/git-repos")
@RequiredArgsConstructor
@RequirePermission({"project:view"})
public class ProjectGitRepoController {

    private final ProjectGitRepoService projectGitRepoService;

    @Operation(summary = "查询仓库绑定列表", description = "返回项目下全部仓库绑定（含停用）")
    @GetMapping
    public Result<List<ProjectGitRepo>> list(
            @Parameter(description = "项目ID") @PathVariable Long projectId) {
        return Result.success(projectGitRepoService.list(projectId));
    }

    @Operation(summary = "绑定或更新仓库", description = "id 为空新增绑定，id 非空更新绑定")
    @PostMapping
    @RequirePermission({"project:manage"})
    public Result<ProjectGitRepo> save(
            @Parameter(description = "项目ID") @PathVariable Long projectId,
            @RequestBody ProjectGitRepoService.RepoRequest request) {
        try {
            return Result.success("保存成功", projectGitRepoService.save(projectId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @Operation(summary = "解绑仓库", description = "删除项目下的一条仓库绑定")
    @DeleteMapping("/{id}")
    @RequirePermission({"project:manage"})
    public Result<Void> delete(
            @Parameter(description = "项目ID") @PathVariable Long projectId,
            @Parameter(description = "绑定ID") @PathVariable Long id) {
        try {
            projectGitRepoService.delete(projectId, id);
            return Result.success("解绑成功", null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
