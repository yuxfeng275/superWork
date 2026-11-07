package com.bu.management.controller;

import com.bu.management.entity.ProjectFact;
import com.bu.management.service.ProjectFactIntakeService;
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
 * <p>全部要求登录态，actor_id 取当前用户；幂等键见 ProjectFactIntakeService。
 */
@Tag(name = "项目事实投递", description = "文件 / 补充说明 / 聊天记录写入项目事实层")
@RestController
@RequestMapping("/api/projects/{projectId}/facts")
@RequiredArgsConstructor
public class ProjectFactIntakeController {

    private final ProjectFactIntakeService intakeService;

    /** 投递说明体。 */
    public record NoteRequest(String title, String body, String visibility) {
    }

    @Operation(summary = "上传本机文件", description = "落盘后写一条 FILE_UPLOAD 事实，同一文件重传不新增")
    @PostMapping("/files")
    public Result<ProjectFact> upload(
            @Parameter(description = "项目ID") @PathVariable Long projectId,
            @Parameter(description = "文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "补充说明") @RequestParam(required = false) String note,
            @Parameter(description = "可见性，默认 PROJECT") @RequestParam(required = false) String visibility,
            @Parameter(description = "发生时间（ISO）")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime occurredAt,
            @RequestAttribute("userId") Long userId) {
        if (file == null || file.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }
        try (InputStream in = file.getInputStream()) {
            return Result.success(intakeService.intakeFile(projectId, userId,
                    file.getOriginalFilename(), in, note, visibility, occurredAt));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IOException e) {
            return Result.error(500, "文件保存失败: " + e.getMessage());
        }
    }

    @Operation(summary = "投递补充说明", description = "每次都是一条新事实")
    @PostMapping("/notes")
    public Result<ProjectFact> note(
            @Parameter(description = "项目ID") @PathVariable Long projectId,
            @RequestBody NoteRequest request,
            @RequestAttribute("userId") Long userId) {
        try {
            return Result.success(intakeService.intakeNote(projectId, userId,
                    request.title(), request.body(), request.visibility()));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @Operation(summary = "投递聊天记录", description = "整段记录拼成一条事实，同一段重传不新增")
    @PostMapping("/chat")
    public Result<ProjectFact> chat(
            @Parameter(description = "项目ID") @PathVariable Long projectId,
            @RequestBody ProjectFactIntakeService.ChatRequest request,
            @RequestAttribute("userId") Long userId) {
        try {
            return Result.success(intakeService.intakeChat(projectId, userId,
                    request.messages(), request.visibility()));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @Operation(summary = "投递记录列表", description = "默认只返回文件/说明/聊天/制品四类")
    @GetMapping
    public Result<List<ProjectFact>> list(
            @Parameter(description = "项目ID") @PathVariable Long projectId,
            @Parameter(description = "来源类型，逗号分隔") @RequestParam(required = false) String types,
            @Parameter(description = "条数，默认 50") @RequestParam(required = false, defaultValue = "50") int limit) {
        return Result.success(intakeService.list(projectId, types, limit));
    }
}
