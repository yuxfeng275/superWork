package com.bu.management.controller;

import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.ProjectFact;
import com.bu.management.service.AiFileService;
import com.bu.management.service.AutomationRunService;
import com.bu.management.service.ProjectBriefPushService;
import com.bu.management.service.ProjectBriefService;
import com.bu.management.service.ProjectFactIntakeService;
import com.bu.management.service.ProjectFactService;
import com.bu.management.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * agent-autoloop 读取项目事实、回写晨报的内部端点（/internal/project-context/**）。
 *
 * <p>与 /internal/ai-agent/** 同口径：SecurityConfig 放行，靠 X-Sidecar-Token 常量时间比对防护。
 * 这里只读项目事实 + 写晨报；写业务数据仍然只能走带用户身份的业务接口。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ProjectContextInternalController {

    private final ProjectFactService factService;
    private final ProjectFactIntakeService intakeService;
    private final ProjectBriefService briefService;
    private final ProjectBriefPushService briefPushService;
    private final AutomationRunService automationRunService;
    private final ProjectContextProperties properties;
    private final AiFileService aiFileService;

    /** 晨报回写请求体。 */
    public record BriefWriteRequest(Long userId, Long projectId, LocalDate briefDate,
                                    String content, String sourceTask) {
    }

    /** 文件投递回执（内部通道：邮件转发 / 目录同步）。 */
    public record FileUploadResult(Long fileId, String filename, Long sizeBytes, Long factId,
                                   boolean deduped) {
    }

    /**
     * 内部文件投递：邮件转发与目录同步器用同一个入口，来源记在 {@code source}。
     *
     * <p>与网页上传共用 {@link AiFileService}：鉴权靠 X-Sidecar-Token + 显式 userId，
     * 项目写权限仍由服务层按该 userId 判定，不因为走内部通道而放宽。
     */
    @PostMapping("/internal/project-context/files")
    public ResponseEntity<?> uploadFile(@RequestParam Long userId,
            @RequestParam Long projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String sourceRef,
            HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Result.error(400, "文件为空"));
        }
        try (java.io.InputStream in = file.getInputStream()) {
            AiFileService.StoredFile stored = aiFileService.store(userId, projectId,
                    file.getOriginalFilename(), in, file.getContentType(), file.getSize(),
                    StringUtils.hasText(source) ? source : "EMAIL", sourceRef);
            return ResponseEntity.ok(Result.success(new FileUploadResult(stored.file().getId(),
                    stored.file().getFilename(), stored.file().getSizeBytes(), stored.factId(),
                    stored.deduped())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        } catch (Exception e) {
            log.warn("内部文件投递失败：userId={} projectId={} {}", userId, projectId, e.getMessage());
            return ResponseEntity.internalServerError().body(Result.error(500, "文件投递失败"));
        }
    }

    @GetMapping("/internal/project-context/{projectId}/facts")
    public ResponseEntity<?> facts(@PathVariable Long projectId,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime since,
            @RequestParam(required = false, defaultValue = "100") int limit,
            HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        List<String> typeList = StringUtils.hasText(types)
                ? Arrays.stream(types.split(",")).map(String::strip).filter(StringUtils::hasText).toList()
                : List.of();
        List<ProjectFact> facts = factService.query(projectId, typeList, since, limit);
        return ResponseEntity.ok(Result.success(facts));
    }

    @PostMapping("/internal/project-context/{projectId}/ingest")
    public ResponseEntity<?> ingest(@PathVariable Long projectId,
            @RequestParam(required = false, defaultValue = "24") int hours,
            HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        int changed = factService.ingestProject(projectId, LocalDateTime.now().minusHours(Math.max(hours, 1)));
        return ResponseEntity.ok(Result.success(changed));
    }

    /** 群机器人 / 脚本直接投递聊天记录：没有登录态，actorId 记 null。 */
    @PostMapping("/internal/project-context/{projectId}/chat")
    public ResponseEntity<?> chat(@PathVariable Long projectId,
            @RequestBody ProjectFactIntakeService.ChatRequest body,
            HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        try {
            return ResponseEntity.ok(Result.success(intakeService.intakeChat(
                    projectId, null, body.messages(), body.visibility())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    /** 无人在场的一轮 Agent 运行（agent-autoloop 的 command 通道入口）。 */
    public record AutomationRunRequest(Long userId, Long projectId, String title, String prompt,
                                       String mode, String systemPrompt) {
    }

    @PostMapping("/internal/project-context/run")
    public ResponseEntity<?> run(@RequestBody AutomationRunRequest body, HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        try {
            return ResponseEntity.ok(Result.success(automationRunService.run(
                    body.userId(), body.projectId(), body.title(), body.prompt(),
                    AutomationRunService.Mode.from(body.mode()), body.systemPrompt())));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    @PostMapping("/internal/project-context/briefs")
    public ResponseEntity<?> writeBrief(@RequestBody BriefWriteRequest body, HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        try {
            Long id = briefService.save(body.userId(), body.projectId(), body.briefDate(),
                    body.content(), body.sourceTask());
            return ResponseEntity.ok(Result.success(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        }
    }

    /** 手动触发晨报推送（agent-autoloop / 运维脚本用）；date 缺省今天。返回成功推送条数。 */
    @PostMapping("/internal/project-context/push")
    public ResponseEntity<?> push(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        return ResponseEntity.ok(Result.success(briefPushService.pushDate(date)));
    }

    /** 未配置令牌（开发环境）直接放行；配置后做常量时间比对。 */
    private boolean tokenOk(HttpServletRequest request) {
        String expected = properties.getToken();
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        String actual = request.getHeader("X-Sidecar-Token");
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
