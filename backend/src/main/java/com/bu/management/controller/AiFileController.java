package com.bu.management.controller;

import com.bu.management.entity.AiFile;
import com.bu.management.service.AiFileService;
import com.bu.management.vo.Result;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 统一空间的文件入口：投递、列表、下载。
 *
 * <p>权限全部委托给 {@link AiFileService}（项目写/读权限），本类不做业务判断，
 * 只负责把参数收干净、把错误翻译成 400。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@RestController
@RequestMapping("/api/ai-files")
@RequiredArgsConstructor
public class AiFileController {

    private final AiFileService fileService;

    /**
     * 投递一份文件到项目空间。
     *
     * @param userId    当前登录用户
     * @param projectId 归属项目
     * @param file      文件
     * @param source    UPLOAD（默认）/ EMAIL / SYNC_DIR / AGENT
     * @param sourceRef 来源标识（邮件 id、目录路径等）
     */
    @PostMapping
    public Result<UploadResult> upload(@RequestAttribute("userId") Long userId,
                                       @RequestParam Long projectId,
                                       @RequestParam("file") MultipartFile file,
                                       @RequestParam(required = false) String source,
                                       @RequestParam(required = false) String sourceRef) {
        if (file == null || file.isEmpty()) {
            return Result.error(400, "文件为空");
        }
        try (InputStream in = file.getInputStream()) {
            AiFileService.StoredFile stored = fileService.store(userId, projectId,
                    file.getOriginalFilename(), in, file.getContentType(), file.getSize(),
                    source, sourceRef);
            AiFile f = stored.file();
            return Result.success(new UploadResult(f.getId(), f.getFilename(), f.getSizeBytes(),
                    f.getSha256(), stored.factId(), stored.deduped()));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "文件投递失败：" + e.getMessage());
        }
    }

    /** 项目文件列表。 */
    @GetMapping
    public Result<List<FileVO>> list(@RequestAttribute("userId") Long userId,
                                     @RequestParam Long projectId,
                                     @RequestParam(defaultValue = "20") int limit) {
        try {
            List<FileVO> rows = fileService.list(userId, projectId, limit).stream()
                    .map(f -> new FileVO(f.getId(), f.getFilename(), f.getContentType(),
                            f.getSizeBytes(), f.getSource(), f.getSourceRef(),
                            f.getOwnerUserId(), f.getCreatedAt() == null ? null : f.getCreatedAt().toString()))
                    .toList();
            return Result.success(rows);
        } catch (IllegalArgumentException e) {
            return Result.error(403, e.getMessage());
        }
    }

    /** 下载原文。 */
    @GetMapping("/{id}/content")
    public ResponseEntity<?> content(@RequestAttribute("userId") Long userId,
                                     @PathVariable Long id) {
        AiFileService.DownloadedFile file;
        try {
            file = fileService.download(userId, id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(403).body(Result.error(403, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Result.error(500, e.getMessage()));
        }
        String name = URLEncoder.encode(file.file().getFilename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        InputStream body = file.content();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + name)
                .contentType(MediaType.parseMediaType(
                        file.file().getContentType() == null
                                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                                : file.file().getContentType()))
                .body(new InputStreamResource(body));
    }

    /** 投递回执：文件行、事实行与是否命中已有文件。 */
    public record UploadResult(Long fileId, String filename, Long sizeBytes, String sha256,
                               Long factId, boolean deduped) {
    }

    /** 列表行。 */
    public record FileVO(Long id, String filename, String contentType, Long sizeBytes,
                         String source, String sourceRef, Long ownerUserId, String createdAt) {
    }
}
