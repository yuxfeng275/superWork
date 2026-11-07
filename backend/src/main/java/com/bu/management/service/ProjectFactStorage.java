package com.bu.management.service;

import com.bu.management.config.ProjectContextProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 项目事实投递的文件落盘：先写 {root}/tmp 临时文件（边写边算 sha256），
 * 再原子 move 到 {root}/{projectId}/{sha256前16}.{ext}。写法对齐 AiAgentAttachmentStorage。
 *
 * <p>同一文件重复上传 sha256 相同 → 落到同一个文件、同一条 source_ref，天然幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectFactStorage {

    private static final String TMP_DIR = "tmp";
    private static final int MAX_EXT_LENGTH = 8;
    /** 幂等文件名取 sha256 前 16 位 */
    private static final int HASH_PREFIX = 16;
    /** 文本类文件最多读取的字节数（超出不整段读入内存） */
    private static final int TEXT_READ_BYTES = 256 * 1024;
    private static final int COPY_BUFFER = 8192;

    private final ProjectContextProperties properties;

    /** 落盘结果：相对路径 + sha256（hex 小写）+ 字节数。 */
    public record StoredFactFile(String relativePath, String sha256, long sizeBytes) {
    }

    /**
     * 落盘；超过 {@code project-context.fact-max-file-mb} 抛 IllegalArgumentException。
     */
    public StoredFactFile store(Long projectId, String fileName, InputStream in) throws IOException {
        if (projectId == null) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        if (in == null) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        Path root = root();
        Path tmpDir = root.resolve(TMP_DIR);
        Files.createDirectories(tmpDir);
        Path tmpFile = tmpDir.resolve(UUID.randomUUID() + ".part");
        long maxBytes = Math.max(properties.getFactMaxFileMb(), 1) * 1024L * 1024L;

        MessageDigest digest = sha256Digest();
        long sizeBytes = 0;
        byte[] buffer = new byte[COPY_BUFFER];
        try (OutputStream out = Files.newOutputStream(tmpFile)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                sizeBytes += read;
                if (sizeBytes > maxBytes) {
                    throw new IllegalArgumentException(
                            "文件超过 " + properties.getFactMaxFileMb() + "MB 上限");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmpFile);
            throw e;
        }

        String sha256 = HexFormat.of().formatHex(digest.digest());
        String relativePath = projectId + "/" + sha256.substring(0, HASH_PREFIX)
                + "." + extension(fileName);
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        try {
            Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 部分文件系统不支持原子 move，退回普通 move
            Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new StoredFactFile(relativePath, sha256, sizeBytes);
    }

    public InputStream open(String relativePath) throws IOException {
        Path path = root().resolve(relativePath).normalize();
        if (!path.startsWith(root().normalize())) {
            throw new IOException("非法文件路径");
        }
        return Files.newInputStream(path);
    }

    /** 读取文本前若干字符；读不到返回 null（正文只是资料，失败不影响落事实）。 */
    public String readText(String relativePath, int maxChars) {
        try (InputStream in = open(relativePath)) {
            byte[] bytes = in.readNBytes(TEXT_READ_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() > maxChars ? text.substring(0, maxChars) : text;
        } catch (IOException e) {
            log.warn("项目事实正文读取失败: {}", relativePath, e);
            return null;
        }
    }

    private Path root() {
        return Path.of(properties.getFactStorageDir());
    }

    /** 取扩展名（小写、仅 [a-z0-9]、最长 8 位），不可用时落 "bin"。 */
    private static String extension(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename;
        int dot = name.lastIndexOf('.');
        String raw = dot >= 0 ? name.substring(dot + 1) : "";
        StringBuilder ext = new StringBuilder();
        for (int i = 0; i < raw.length() && ext.length() < MAX_EXT_LENGTH; i++) {
            char c = Character.toLowerCase(raw.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                ext.append(c);
            }
        }
        return ext.isEmpty() ? "bin" : ext.toString().toLowerCase(Locale.ROOT);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
