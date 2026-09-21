package com.bu.management.service;

import com.bu.management.config.AiAgentAttachmentProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 助手附件本地文件存储：先写 {storageDir}/tmp 临时文件（边写边算 sha256），
 * 再原子 move 到 s{sessionId}/{uuid}.{ext}。对齐会议录音的落盘模式。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAgentAttachmentStorage {

    private static final String TMP_DIR = "tmp";
    private static final int MAX_EXT_LENGTH = 8;
    /** 文本摘要最多读取的字节数（超出直接截断，不整段读入内存） */
    private static final int EXCERPT_READ_BYTES = 256 * 1024;
    /** 注入给模型的文本摘要字符上限 */
    private static final int EXCERPT_CHARS = 8000;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "csv", "json", "xml", "log",
            "js", "ts", "tsx", "jsx", "java", "py", "yml", "yaml", "html", "css", "sql");

    private final AiAgentAttachmentProperties properties;

    /** 落盘结果：相对路径 + 字节数 + sha256（hex 小写）。 */
    public record StoredAttachment(String relativePath, long sizeBytes, String sha256) {
    }

    /** 业务校验（大小阈值）由调用方负责。 */
    public StoredAttachment store(Long sessionId, String originalFilename, InputStream in)
            throws IOException {
        Path root = root();
        Path tmpDir = root.resolve(TMP_DIR);
        Files.createDirectories(tmpDir);
        Path tmpFile = tmpDir.resolve(UUID.randomUUID() + ".part");
        String relativePath = "s" + sessionId + "/" + UUID.randomUUID() + "." + extension(originalFilename);

        MessageDigest digest = sha256Digest();
        long sizeBytes;
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(tmpFile), digest)) {
            sizeBytes = in.transferTo(out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmpFile);
            throw e;
        }

        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return new StoredAttachment(relativePath, sizeBytes,
                HexFormat.of().formatHex(digest.digest()));
    }

    public InputStream open(String relativePath) throws IOException {
        Path path = root().resolve(relativePath).normalize();
        if (!path.startsWith(root().normalize())) {
            throw new IOException("非法附件路径");
        }
        return Files.newInputStream(path);
    }

    /**
     * 文本类附件抽取正文摘要（≤8000 字）；非文本类型返回 null。
     */
    public String excerpt(String relativePath, String fileName) {
        if (!isTextLike(fileName)) {
            return null;
        }
        try (InputStream in = open(relativePath)) {
            byte[] bytes = in.readNBytes(EXCERPT_READ_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.length() > EXCERPT_CHARS) {
                return text.substring(0, EXCERPT_CHARS) + "\n…(已截断)";
            }
            return text;
        } catch (IOException e) {
            log.warn("AI 附件摘要读取失败: {}", relativePath, e);
            return null;
        }
    }

    /** best-effort 删除：异常仅记日志。 */
    public void delete(String relativePath) {
        try {
            Path path = root().resolve(relativePath).normalize();
            if (path.startsWith(root().normalize())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("AI 附件删除失败: {}", relativePath, e);
        }
    }

    private boolean isTextLike(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private Path root() {
        return Path.of(properties.getStorageDir());
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
        return ext.isEmpty() ? "bin" : ext.toString();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
