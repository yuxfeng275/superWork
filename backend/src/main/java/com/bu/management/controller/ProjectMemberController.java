package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.ProjectMemberRequest;
import com.bu.management.entity.ProjectMember;
import com.bu.management.service.ProjectMemberService;
import com.bu.management.vo.ProjectMemberVO;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目成员控制器
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Tag(name = "项目成员管理", description = "项目成员的添加、移除、查询接口")
@RestController
@RequestMapping("/api/project-members")
@RequiredArgsConstructor
@RequirePermission({"project:view"})
public class ProjectMemberController {

    private final ProjectMemberService projectMemberService;

    /**
     * 添加项目成员
     */
    @Operation(summary = "添加项目成员", description = "将用户添加到项目中")
    @PostMapping
    @RequirePermission({"project:manage"})
    public Result<ProjectMember> addMember(@Valid @RequestBody ProjectMemberRequest request) {
        ProjectMember member = projectMemberService.addMember(request);
        return Result.success("添加成功", member);
    }

    /**
     * 移除项目成员
     */
    @Operation(summary = "移除项目成员", description = "将用户从项目中移除")
    @DeleteMapping
    @RequirePermission({"project:manage"})
    public Result<Void> removeMember(
            @Parameter(description = "项目ID") @RequestParam Long projectId,
            @Parameter(description = "用户ID") @RequestParam Long userId) {
        projectMemberService.removeMember(projectId, userId);
        return Result.success("移除成功", null);
    }

    /**
     * 查询项目成员列表
     */
    @Operation(summary = "查询项目成员列表", description = "获取指定项目的所有成员")
    @GetMapping("/by-project")
    public Result<List<ProjectMemberVO>> listByProject(
            @Parameter(description = "项目ID") @RequestParam Long projectId) {
        List<ProjectMemberVO> members = projectMemberService.listByProject(projectId);
        return Result.success(members);
    }

    /**
     * 查询用户参与的项目列表
     */
    @Operation(summary = "查询用户参与的项目", description = "获取用户参与的所有项目")
    @GetMapping("/by-user")
    public Result<List<ProjectMemberVO>> listByUser(
            @Parameter(description = "用户ID") @RequestParam Long userId) {
        List<ProjectMemberVO> members = projectMemberService.listByUser(userId);
        return Result.success(members);
    }
}
