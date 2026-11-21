package com.bu.management.service.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;

/**
 * 本地盘实现：沿用 {@code ProjectFactStorage} 的做法——同目录临时文件 + 原子改名，
 * 避免进程在写入中途退出时留下半截文件。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
public class LocalObjectStore implements ObjectStore {

    private static final String TMP_DIR = "tmp";

    private final Path root;

    public LocalObjectStore(Path root) {
        this.root = root;
    }

    @Override
    public void put(String key, Path source, String contentType) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path tmpDir = root.resolve(TMP_DIR);
        Files.createDirectories(tmpDir);
        Path tmp = Files.createTempFile(tmpDir, "put-", ".part");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    /** 键只能是相对路径；任何越界（绝对路径、..）一律拒绝。 */
    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root.normalize())) {
            throw new IllegalArgumentException("非法的对象键：" + key);
        }
        return path;
    }
}
