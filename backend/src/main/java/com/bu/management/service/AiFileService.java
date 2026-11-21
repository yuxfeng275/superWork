package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiFile;
import com.bu.management.mapper.AiFileMapper;
import com.bu.management.service.objectstore.ObjectStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 统一空间：文件投递与原始读取。
 *
 * <p>一次投递做四件事，顺序固定：
 * <ol>
 *   <li>按项目写权限校验（{@link ProjectFactAccessGuard#canWrite}）；</li>
 *   <li>算 sha256，命中同项目同哈希则复用已有文件行（不重复存原文）；</li>
 *   <li>原文写入 {@link ObjectStore}（键只由 projectId + sha256 决定，天然幂等）；</li>
 *   <li>写一条 {@code FILE_UPLOAD} 事实，source_ref = {@code file:<id>}，让夜间任务能取到证据。</li>
 * </ol>
 *
 * <p>刻意不做的事：不解析正文入库（原文只在对象存储，事实层只留摘要）、不自动归类到其他项目、
 * 不在这里判断「这份文件值不值得让 Agent 看」——那是候选筛选的事。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFileService {

    /** 摘要里最多带多少字符的文本开头（只对文本类文件）。 */
    private static final int TEXT_HEAD_CHARS = 400;

    /** 判定「文本类」的扩展名，仅用于生成摘要，不影响存储。 */
    private static final List<String> TEXT_EXTENSIONS = List.of(
            "txt", "md", "csv", "json", "log", "sql", "yml", "yaml", "xml", "html");

    /** 一次投递的结果：文件行 + 事实行 + 是否命中已有文件。 */
    public record StoredFile(AiFile file, Long factId, boolean deduped) {
    }

    private final AiFileMapper fileMapper;

    private final ObjectStore objectStore;

    private final ProjectFactAccessGuard accessGuard;

    private final ProjectFactService factService;

    @Value("${ai.file.max-mb:50}")
    private int maxFileMb;

    /**
     * 投递一份文件。
     *
     * @param userId    投递人
     * @param projectId 归属项目
     * @param filename  原始文件名
     * @param in        内容流（调用方负责关闭）
     * @param source    UPLOAD / EMAIL / SYNC_DIR / AGENT
     * @param sourceRef 邮件 id / 目录路径 / run 号
     * @param sizeHint  已知字节数，未知传 -1
     */
    public StoredFile store(Long userId, Long projectId, String filename, InputStream in,
                            String contentType, long sizeHint, String source, String sourceRef) {
        if (projectId == null) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        if (!accessGuard.canWrite(userId, projectId)) {
            throw new IllegalArgumentException("无权向该项目投递文件");
        }
        if (in == null) {
            throw new IllegalArgumentException("文件内容为空");
        }
        long maxBytes = Math.max(1, maxFileMb) * 1024L * 1024L;
        if (sizeHint > maxBytes) {
            throw new IllegalArgumentException("文件超过上限 " + maxFileMb + "MB");
        }

        Path tmp = null;
        try {
            tmp = Files.createTempFile("ai-file-", ".part");
            long size = Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (size > maxBytes) {
                throw new IllegalArgumentException("文件超过上限 " + maxFileMb + "MB");
            }
            if (size <= 0) {
                throw new IllegalArgumentException("文件内容为空");
            }
            String sha = sha256(tmp);
            String safeName = normalizeName(filename);
            String objectKey = objectKey(projectId, sha, safeName);
            String mime = StringUtils.hasText(contentType) ? contentType : guessContentType(safeName);

            AiFile existing = findExisting(projectId, sha);
            boolean deduped = existing != null;
            AiFile target;
            if (deduped) {
                target = existing;
                target.setUpdatedAt(LocalDateTime.now());
                fileMapper.updateById(target);
                log.info("文件去重命中：projectId={} fileId={} sha={}", projectId, target.getId(), sha);
            } else {
                objectStore.put(objectKey, tmp, mime);
                target = new AiFile();
                target.setProjectId(projectId);
                target.setOwnerUserId(userId);
                target.setFilename(safeName);
                target.setContentType(mime);
                target.setSizeBytes(size);
                target.setSha256(sha);
                target.setObjectKey(objectKey);
                target.setSource(StringUtils.hasText(source) ? source : "UPLOAD");
                target.setSourceRef(sourceRef);
                target.setVisibility(ProjectFactService.VISIBILITY_PROJECT);
                target.setCreatedAt(LocalDateTime.now());
                target.setUpdatedAt(LocalDateTime.now());
                fileMapper.insert(target);
            }

            String digestText = digestText(target, tmp);
            int changed = factService.upsertFileFact(projectId, userId, target.getId(),
                    target.getFilename(), digestText, LocalDateTime.now(), target.getVisibility());
            Long factId = null;
            if (changed > 0) {
                var fact = factService.find(ProjectFactService.SOURCE_FILE_UPLOAD, "file:" + target.getId());
                factId = fact == null ? null : fact.getId();
            }
            return new StoredFile(target, factId, deduped);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("文件投递失败：" + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响投递结果
                }
            }
        }
    }

    /** 列表：只返回未删除的文件；调用方需已通过读权限校验。 */
    public List<AiFile> list(Long userId, Long projectId, int limit) {
        if (!accessGuard.canRead(userId, projectId)) {
            throw new IllegalArgumentException("无权查看该项目文件");
        }
        int size = limit <= 0 ? 20 : Math.min(limit, 100);
        return fileMapper.selectList(new LambdaQueryWrapper<AiFile>()
                .eq(AiFile::getProjectId, projectId)
                .isNull(AiFile::getDeletedAt)
                .orderByDesc(AiFile::getCreatedAt)
                .last("limit " + size));
    }

    /** 读原文；返回流由调用方关闭。 */
    public DownloadedFile download(Long userId, Long fileId) {
        AiFile file = fileMapper.selectById(fileId);
        if (file == null || file.getDeletedAt() != null) {
            throw new IllegalArgumentException("文件不存在");
        }
        if (!accessGuard.canRead(userId, file.getProjectId())) {
            throw new IllegalArgumentException("无权读取该文件");
        }
        try {
            return new DownloadedFile(file, objectStore.open(file.getObjectKey()));
        } catch (Exception e) {
            throw new IllegalStateException("读取文件失败：" + e.getMessage(), e);
        }
    }

    /** 下载结果：元数据 + 内容流。 */
    public record DownloadedFile(AiFile file, InputStream content) {
    }

    private AiFile findExisting(Long projectId, String sha) {
        return fileMapper.selectOne(new LambdaQueryWrapper<AiFile>()
                .eq(AiFile::getProjectId, projectId)
                .eq(AiFile::getSha256, sha)
                .isNull(AiFile::getDeletedAt)
                .last("limit 1"));
    }

    /** 摘要文本：文件名 + 大小 + 文本类文件的开头；绝不把原文整段塞进事实层。 */
    private String digestText(AiFile file, Path local) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(file.getSource()).append("] ")
                .append(file.getFilename())
                .append("（").append(humanSize(file.getSizeBytes())).append('）');
        String ext = extension(file.getFilename());
        if (TEXT_EXTENSIONS.contains(ext)) {
            try (InputStream in = Files.newInputStream(local)) {
                byte[] buf = in.readNBytes(TEXT_HEAD_CHARS * 4);
                String text = new String(buf, StandardCharsets.UTF_8)
                        .replaceAll("\\s+", " ").trim();
                if (text.length() > TEXT_HEAD_CHARS) {
                    text = text.substring(0, TEXT_HEAD_CHARS) + "…";
                }
                if (!text.isEmpty()) {
                    sb.append("：").append(text);
                }
            } catch (IOException e) {
                log.debug("摘要读取文本失败：{}", e.getMessage());
            }
        }
        return sb.toString();
    }

    private String objectKey(Long projectId, String sha, String filename) {
        String ext = extension(filename);
        String suffix = ext.isEmpty() ? "" : "." + ext;
        return projectId + "/" + sha.substring(0, 2) + "/" + sha + suffix;
    }

    private String normalizeName(String filename) {
        String name = StringUtils.hasText(filename) ? filename.strip() : "未命名文件";
        name = name.replaceAll("[\\\\/\\r\\n\\t]", "_");
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    private String guessContentType(String filename) {
        String ext = extension(filename);
        return switch (ext) {
            case "txt", "md", "log", "csv" -> "text/plain";
            case "json" -> "application/json";
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/octet-stream";
        };
    }

    private static String extension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        String name = filename.strip();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.length() > 8 ? "" : ext;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
