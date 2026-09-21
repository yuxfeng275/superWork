package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bu.management.config.AiAgentAttachmentProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiAgentAttachmentStorageTest {

    @TempDir
    Path tempDir;

    private AiAgentAttachmentStorage storage() {
        AiAgentAttachmentProperties properties = new AiAgentAttachmentProperties();
        properties.setStorageDir(tempDir.toString());
        return new AiAgentAttachmentStorage(properties);
    }

    @Test
    @DisplayName("store 落盘到会话子目录并返回 size/sha256，临时文件不残留")
    void storeComputesSizeAndSha256() throws Exception {
        byte[] payload = "fake-notes".getBytes(StandardCharsets.UTF_8);

        AiAgentAttachmentStorage.StoredAttachment stored =
                storage().store(3L, "notes.MD", new ByteArrayInputStream(payload));

        assertThat(stored.relativePath()).startsWith("s3/").endsWith(".md");
        assertThat(stored.sizeBytes()).isEqualTo(payload.length);
        assertThat(stored.sha256()).isEqualTo(HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
        assertThat(Files.readAllBytes(tempDir.resolve(stored.relativePath()))).isEqualTo(payload);
        try (Stream<Path> leftovers = Files.list(tempDir.resolve("tmp"))) {
            assertThat(leftovers).isEmpty();
        }
    }

    @Test
    @DisplayName("open 读回原始字节；未知扩展名落 .bin")
    void openReturnsStoredBytesAndExtensionFallsBack() throws Exception {
        byte[] payload = {1, 2, 3, 4, 5};
        AiAgentAttachmentStorage.StoredAttachment stored =
                storage().store(7L, "no-extension", new ByteArrayInputStream(payload));
        assertThat(stored.relativePath()).endsWith(".bin");

        try (var in = storage().open(stored.relativePath())) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    @DisplayName("delete 删除文件；重复删除与越界路径不抛异常")
    void deleteIsBestEffort() throws Exception {
        AiAgentAttachmentStorage.StoredAttachment stored =
                storage().store(9L, "a.txt", new ByteArrayInputStream(new byte[] {1}));

        storage().delete(stored.relativePath());
        assertThat(Files.exists(tempDir.resolve(stored.relativePath()))).isFalse();

        storage().delete(stored.relativePath());
        storage().delete("../escape.txt");
    }

    @Test
    @DisplayName("文本附件可抽取摘要；超长截断；二进制返回空")
    void excerptExtraction() throws Exception {
        byte[] text = "会议纪要正文".getBytes(StandardCharsets.UTF_8);
        AiAgentAttachmentStorage.StoredAttachment stored =
                storage().store(1L, "minutes.md", new ByteArrayInputStream(text));
        assertThat(storage().excerpt(stored.relativePath(), "minutes.md"))
                .isEqualTo("会议纪要正文");

        String longText = "长".repeat(9000);
        AiAgentAttachmentStorage.StoredAttachment longStored = storage().store(1L,
                "long.txt", new ByteArrayInputStream(longText.getBytes(StandardCharsets.UTF_8)));
        String excerpt = storage().excerpt(longStored.relativePath(), "long.txt");
        assertThat(excerpt).hasSize(8000 + "\n…(已截断)".length());

        AiAgentAttachmentStorage.StoredAttachment binary = storage().store(1L,
                "photo.png", new ByteArrayInputStream(new byte[] {9, 9}));
        assertThat(storage().excerpt(binary.relativePath(), "photo.png")).isNull();
    }
}
