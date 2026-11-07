package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.entity.AiArtifact;
import com.bu.management.service.AiArtifactService;
import com.bu.management.service.ProjectFactAccessGuard;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
 * <p>口径见 docs/project-agent-loop-plan.md §5.3：类级 {@code project:view} 只说明「登录且能看项目」，
 * 具体某个项目的制品还要过 {@link ProjectFactAccessGuard} 的按项目准入（管理角色 / 项目负责人 /
 * project_member / {@code project:manage}），不通过一律 403：
 * <ul>
 *   <li>列表必须带 projectId（不做无项目全量扫），需 canRead；</li>
 *   <li>待我评审按 canRead 过滤，只返回有准入的项目，不整体 403；</li>
 *   <li>提交评审保留「产出人本人 + project:view」（产出人可能是非经理），另需 canRead；</li>
 *   <li>裁决需 canRead；采纳会回写 project_fact，需 canWrite。</li>
 * </ul>
 * 状态流转（提交只认 DRAFT/REJECTED、裁决不能评自己的、采纳只认 APPROVED）仍由 service 校验。
 * 所有 IllegalArgumentException 一律转 400，前端直接展示消息。
 */
@Tag(name = "AI 制品与评审", description = "夜间产出的制品列表、提交评审、裁决与采纳回流")
@RestController
@RequestMapping("/api/ai-artifacts")
@RequiredArgsConstructor
@RequirePermission({"project:view"})
public class AiArtifactController {

    /** 无权读该项目制品时的统一文案。 */
    private static final String NO_READ = "无权访问该项目的制品";

    /** 无权采纳该项目制品时的统一文案（采纳会回写项目事实）。 */
    private static final String NO_WRITE = "无权采纳该项目的制品";

    private final AiArtifactService artifactService;
    private final ProjectFactAccessGuard accessGuard;

    /** 裁决请求体。 */
    public record DecideRequest(Boolean approve, String reason) {
    }

    @Operation(summary = "项目制品列表", description = "必须带 projectId；status 为空返回全部状态，按 id 降序")
    @GetMapping
    public ResponseEntity<?> list(
            @RequestAttribute("userId") Long userId,
            @Parameter(description = "项目ID") @RequestParam(required = false) Long projectId,
            @Parameter(description = "状态过滤：DRAFT/IN_REVIEW/APPROVED/REJECTED/APPLIED")
            @RequestParam(required = false) String status) {
        if (projectId == null) {
            return ResponseEntity.badRequest().body(Result.error(400, "项目 ID 不能为空"));
        }
        if (!accessGuard.canRead(userId, projectId)) {
            return ResponseEntity.status(403).body(Result.error(403, NO_READ));
        }
        try {
            return ResponseEntity.ok(Result.success(artifactService.listByProject(projectId, status)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "待我评审", description = "他人产出的制品，按项目准入过滤；可按 status 过滤（评审队列传 IN_REVIEW）")
    @GetMapping("/my-reviews")
    public ResponseEntity<?> myReviews(
            @RequestAttribute("userId") Long userId,
            @Parameter(description = "状态过滤，可选") @RequestParam(required = false) String status) {
        try {
            List<AiArtifact> reviews = artifactService.listMyReviews(userId, status).stream()
                    .filter(artifact -> accessGuard.canRead(userId, artifact.getProjectId()))
                    .toList();
            return ResponseEntity.ok(Result.success(reviews));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "创建制品草稿", description = "必须挂证据（project_fact.id 数组），状态恒为 DRAFT")
    @PostMapping
    @RequirePermission({"project:manage"})
    public ResponseEntity<?> create(
            @RequestAttribute("userId") Long userId,
            @RequestBody AiArtifactService.ArtifactCreateRequest request) {
        Long projectId = request == null ? null : request.projectId();
        if (projectId != null && !accessGuard.canWrite(userId, projectId)) {
            return ResponseEntity.status(403).body(Result.error(403, NO_WRITE));
        }
        try {
            return ResponseEntity.ok(Result.success("创建成功",
                    artifactService.create(userId, projectId, request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "提交评审", description = "DRAFT/REJECTED → IN_REVIEW，仅产出人可提交")
    @PostMapping("/{id}/submit")
    @RequirePermission({"project:view"})
    public ResponseEntity<?> submit(
            @RequestAttribute("userId") Long userId,
            @Parameter(description = "制品ID") @PathVariable Long id) {
        AiArtifact artifact;
        try {
            artifact = artifactService.get(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
        // 提交保留「本人 + project:view」（产出人可能是非经理），但读取仍需项目准入
        if (!accessGuard.canRead(userId, artifact.getProjectId())) {
            return ResponseEntity.status(403).body(Result.error(403, NO_READ));
        }
        try {
            return ResponseEntity.ok(Result.success("已提交评审", artifactService.submit(userId, id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "评审裁决", description = "IN_REVIEW → APPROVED/REJECTED；驳回必须带原因；需项目读准入")
    @PostMapping("/{id}/decide")
    @RequirePermission({"project:view"})
    public ResponseEntity<?> decide(
            @RequestAttribute("userId") Long reviewerId,
            @Parameter(description = "制品ID") @PathVariable Long id,
            @RequestBody DecideRequest request) {
        if (request == null || request.approve() == null) {
            return ResponseEntity.badRequest().body(Result.error(400, "approve 不能为空"));
        }
        AiArtifact artifact;
        try {
            artifact = artifactService.get(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
        if (!accessGuard.canRead(reviewerId, artifact.getProjectId())) {
            return ResponseEntity.status(403).body(Result.error(403, NO_READ));
        }
        try {
            return ResponseEntity.ok(Result.success(request.approve() ? "已通过" : "已驳回",
                    artifactService.decide(reviewerId, id, request.approve(), request.reason())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "采纳制品", description = "APPROVED → APPLIED，并回写一条项目事实；需项目写准入")
    @PostMapping("/{id}/apply")
    @RequirePermission({"project:view"})
    public ResponseEntity<?> apply(
            @RequestAttribute("userId") Long userId,
            @Parameter(description = "制品ID") @PathVariable Long id) {
        AiArtifact artifact;
        try {
            artifact = artifactService.get(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
        // 采纳会回写该项目 project_fact，按写准入判
        if (!accessGuard.canWrite(userId, artifact.getProjectId())) {
            return ResponseEntity.status(403).body(Result.error(403, NO_WRITE));
        }
        try {
            return ResponseEntity.ok(Result.success("已采纳", artifactService.apply(userId, id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }
}
