package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bu.management.config.MeetingProperties;
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

class MeetingAudioStorageTest {

    @TempDir
    Path tempDir;

    private MeetingAudioStorage storage() {
        MeetingProperties properties = new MeetingProperties();
        properties.setStorageDir(tempDir.toString());
        return new MeetingAudioStorage(properties);
    }

    @Test
    @DisplayName("store 落盘并返回 size/sha256，临时文件不残留")
    void storeComputesSizeAndSha256() throws Exception {
        byte[] payload = "fake-m4a-bytes".getBytes(StandardCharsets.UTF_8);

        MeetingAudioStorage.StoredAudio stored =
                storage().store("42", "meeting.M4A", new ByteArrayInputStream(payload));

        assertThat(stored.relativePath()).isEqualTo("42.m4a");
        assertThat(stored.sizeBytes()).isEqualTo(payload.length);
        assertThat(stored.sha256()).isEqualTo(HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
        try (Stream<Path> leftovers = Files.list(tempDir.resolve("tmp"))) {
            assertThat(leftovers).isEmpty();
        }
    }

    @Test
    @DisplayName("open 读回原始字节；未知扩展名落 .bin")
    void openReturnsStoredBytesAndExtensionFallsBack() throws Exception {
        byte[] payload = {1, 2, 3, 4, 5};
        MeetingAudioStorage.StoredAudio stored =
                storage().store("7", "no-extension", new ByteArrayInputStream(payload));
        assertThat(stored.relativePath()).isEqualTo("7.bin");

        try (var in = storage().open(stored.relativePath())) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    @DisplayName("delete 删除文件；重复删除与越界路径不抛异常")
    void deleteIsBestEffort() throws Exception {
        MeetingAudioStorage.StoredAudio stored =
                storage().store("9", "a.wav", new ByteArrayInputStream(new byte[] {1}));

        storage().delete(stored.relativePath());
        assertThat(Files.exists(tempDir.resolve("9.wav"))).isFalse();

        storage().delete(stored.relativePath());
        storage().delete("../escape.wav");
    }
}
