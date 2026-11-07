package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.entity.ProjectFact;
import com.bu.management.service.ProjectFactAccessGuard;
import com.bu.management.service.ProjectFactIntakeService;
import com.bu.management.service.ProjectFactPolicy;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 项目事实投递入口：成员把本机文件、补充说明、聊天记录灌进 project_fact。
 *
 * <p>准入分两道：类级 {@code project:view}（登录且能看项目），以及
 * {@link ProjectFactAccessGuard} 的按项目判定（管理角色 / 项目负责人 / 项目成员 / {@code project:manage}），
 * 不通过就返回 403。actor_id 取当前用户；幂等键见 ProjectFactIntakeService。
 */
@Tag(name = "项目事实投递", description = "文件 / 补充说明 / 聊天记录写入项目事实层")
@RestController
@RequestMapping("/api/projects/{projectId}/facts")
@RequiredArgsConstructor
@RequirePermission({"project:view"})
public class ProjectFactIntakeController {

    /** 无权访问该项目事实时的统一文案。 */
    private static final String NO_ACCESS = "无权访问该项目的事实";

    private final ProjectFactIntakeService intakeService;
    private final ProjectFactAccessGuard accessGuard;
    /** 可见性口径的唯一真源：与 AI 工具用同一份实现，别再在这里自己拼过滤。 */
    private final ProjectFactPolicy factPolicy;

    /** 投递说明体。 */
    public record NoteRequest(String title, String body, String visibility) {
    }

    @Operation(summary = "上传本机文件", description = "落盘后写一条 FILE_UPLOAD 事实，同一文件重传不新增")
    @PostMapping("/files")
    public ResponseEntity<?> upload(
            @Parameter(description = "项目ID") @PathVariable Long projectId,
            @Parameter(description = "文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "补充说明") @RequestParam(required = false) String note,
            @Parameter(description = "可见性，默认 PROJECT") @RequestParam(required = false) String visibility,
            @Parameter(description = "发生时间（ISO）")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime occurredAt,
            @RequestAttribute("userId") Long userId) {
        if (!accessGuard.canWrite(userId, projectId)) {
            return ResponseEntity.status(403).body(Result.error(403, NO_ACCESS));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Result.error(400, "文件不能为空"));
        }
        try (InputStream in = file.getInputStream()) {
            return ResponseEntity.ok(Result.success(intakeService.intakeFile(projectId, userId,
                    file.getOriginalFilename(), in, note, visibility, occurredAt)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Result.error(500, "文件保存失败: " + e.getMessage()));
        }
    }

    @Operation(summary = "投递补充说明", description = "每次都是一条新事实")
    @PostMapping("/notes")
    public ResponseEntity<?> note(
            @Parameter(description = "项目ID") @PathVariable Long projectId,
            @RequestBody NoteRequest request,
            @RequestAttribute("userId") Long userId) {
        if (!accessGuard.canWrite(userId, projectId)) {
            return ResponseEntity.status(403).body(Result.error(403, NO_ACCESS));
        }
        try {
            return ResponseEntity.ok(Result.success(intakeService.intakeNote(projectId, userId,
                    request.title(), request.body(), request.visibility())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "投递聊天记录", description = "整段记录拼成一条事实，同一段重传不新增")
    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            @Parameter(description = "项目ID") @PathVariable Long projectId,
            @RequestBody ProjectFactIntakeService.ChatRequest request,
            @RequestAttribute("userId") Long userId) {
        if (!accessGuard.canWrite(userId, projectId)) {
            return ResponseEntity.status(403).body(Result.error(403, NO_ACCESS));
        }
        try {
            return ResponseEntity.ok(Result.success(intakeService.intakeChat(projectId, userId,
                    request.messages(), request.visibility())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    @Operation(summary = "投递记录列表",
            description = "默认只返回文件/说明/聊天/制品四类；OWNER 仅投递人本人可见，"
                    + "PRIVATE_COMPANY 仅项目负责人/管理角色可见")
    @GetMapping
    public ResponseEntity<?> list(
            @Parameter(description = "项目ID") @PathVariable Long projectId,
            @Parameter(description = "来源类型，逗号分隔") @RequestParam(required = false) String types,
            @Parameter(description = "条数，默认 50") @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestAttribute("userId") Long userId) {
        if (!accessGuard.canRead(userId, projectId)) {
            return ResponseEntity.status(403).body(Result.error(403, NO_ACCESS));
        }
        return ResponseEntity.ok(Result.success(factPolicy.visible(userId, projectId,
                intakeService.list(projectId, types, limit))));
    }
}
