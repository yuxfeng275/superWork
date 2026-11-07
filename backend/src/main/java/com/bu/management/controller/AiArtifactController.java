package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.entity.AiArtifact;
import com.bu.management.service.AiArtifactService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 制品与评审队列：夜间产出的方案/补丁/修复说明在这里排队等拍板。
 *
 * <p>口径见 docs/project-agent-loop-plan.md §5.3：
 * 写操作（建草稿/提交/裁决/采纳）需 project:manage；读走 project:view。
 * 所有 IllegalArgumentException 一律转 400，前端直接展示消息。
 */
@Tag(name = "AI 制品与评审", description = "夜间产出的制品列表、提交评审、裁决与采纳回流")
@RestController
@RequestMapping("/api/ai-artifacts")
@RequiredArgsConstructor
@RequirePermission({"project:view"})
public class AiArtifactController {

    private final AiArtifactService artifactService;

    /** 裁决请求体。 */
    public record DecideRequest(Boolean approve, String reason) {
    }

    @Operation(summary = "项目制品列表", description = "status 为空返回全部状态，按 id 降序")
    @GetMapping
    public Result<List<AiArtifact>> list(
            @Parameter(description = "项目ID") @RequestParam(required = false) Long projectId,
            @Parameter(description = "状态过滤：DRAFT/IN_REVIEW/APPROVED/REJECTED/APPLIED")
            @RequestParam(required = false) String status) {
        try {
            return Result.success(artifactService.listByProject(projectId, status));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @Operation(summary = "待我评审", description = "他人产出的制品，可按 status 过滤（评审队列传 IN_REVIEW）")
    @GetMapping("/my-reviews")
    public Result<List<AiArtifact>> myReviews(
            @RequestAttribute("userId") Long userId,
            @Parameter(description = "状态过滤，可选") @RequestParam(required = false) String status) {
        try {
            return Result.success(artifactService.listMyReviews(userId, status));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @Operation(summary = "创建制品草稿", description = "必须挂证据（project_fact.id 数组），状态恒为 DRAFT")
    @PostMapping
    @RequirePermission({"project:manage"})
    public Result<AiArtifact> create(
            @RequestAttribute("userId") Long userId,
            @RequestBody AiArtifactService.ArtifactCreateRequest request) {
        try {
            return Result.success("创建成功",
                    artifactService.create(userId, request == null ? null : request.projectId(), request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @Operation(summary = "提交评审", description = "DRAFT/REJECTED → IN_REVIEW，仅产出人可提交")
    @PostMapping("/{id}/submit")
    @RequirePermission({"project:manage"})
    public Result<AiArtifact> submit(
            @RequestAttribute("userId") Long userId,
            @Parameter(description = "制品ID") @PathVariable Long id) {
        try {
            return Result.success("已提交评审", artifactService.submit(userId, id));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @Operation(summary = "评审裁决", description = "IN_REVIEW → APPROVED/REJECTED；驳回必须带原因")
    @PostMapping("/{id}/decide")
    @RequirePermission({"project:manage"})
    public Result<AiArtifact> decide(
            @RequestAttribute("userId") Long reviewerId,
            @Parameter(description = "制品ID") @PathVariable Long id,
            @RequestBody DecideRequest request) {
        try {
            if (request == null || request.approve() == null) {
                return Result.error(400, "approve 不能为空");
            }
            return Result.success(request.approve() ? "已通过" : "已驳回",
                    artifactService.decide(reviewerId, id, request.approve(), request.reason()));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @Operation(summary = "采纳制品", description = "APPROVED → APPLIED，并回写一条项目事实")
    @PostMapping("/{id}/apply")
    @RequirePermission({"project:manage"})
    public Result<AiArtifact> apply(
            @RequestAttribute("userId") Long userId,
            @Parameter(description = "制品ID") @PathVariable Long id) {
        try {
            return Result.success("已采纳", artifactService.apply(userId, id));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
