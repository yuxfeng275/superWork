package com.bu.management.service;

import com.bu.management.config.MeetingProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会议录音本地文件存储：先写 {storageDir}/tmp 临时文件（边写边算 sha256），再原子 move 到 {meetingId}.{ext}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingAudioStorage {

    private static final String TMP_DIR = "tmp";
    private static final int MAX_EXT_LENGTH = 8;

    private final MeetingProperties properties;

    /** 落盘结果：相对路径 + 字节数 + sha256（hex 小写）。 */
    public record StoredAudio(String relativePath, long sizeBytes, String sha256) {
    }

    /** 业务校验（扩展名、大小阈值）由调用方负责；baseName 决定存储文件名（调用方保证唯一）。 */
    public StoredAudio store(String baseName, String originalFilename, InputStream in) throws IOException {
        Path root = root();
        Path tmpDir = root.resolve(TMP_DIR);
        Files.createDirectories(tmpDir);
        Path tmpFile = tmpDir.resolve(UUID.randomUUID() + ".part");
        String relativePath = baseName + "." + extension(originalFilename);

        MessageDigest digest = sha256Digest();
        long sizeBytes;
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(tmpFile), digest)) {
            sizeBytes = in.transferTo(out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmpFile);
            throw e;
        }

        Files.move(tmpFile, root.resolve(relativePath),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new StoredAudio(relativePath, sizeBytes, HexFormat.of().formatHex(digest.digest()));
    }

    public InputStream open(String relativePath) throws IOException {
        return Files.newInputStream(root().resolve(relativePath));
    }

    /** 存储文件路径，供转写客户端流式上传（不整段读入内存）。 */
    public Path pathOf(String relativePath) {
        return root().resolve(relativePath);
    }

    /** best-effort 删除：异常仅记日志。 */
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(root().resolve(relativePath));
        } catch (IOException | RuntimeException e) {
            log.warn("会议录音删除失败: {}", relativePath, e);
        }
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
