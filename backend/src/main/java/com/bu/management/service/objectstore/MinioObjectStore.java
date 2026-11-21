package com.bu.management.service.objectstore;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;

/**
 * MinIO 实现：prod 存原文的地方。
 *
 * <p>桶不存在时创建一次（幂等）；对象键与本地实现完全一致，便于两者之间搬迁。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
public class MinioObjectStore implements ObjectStore {

    private final MinioClient client;

    private final String bucket;

    private volatile boolean bucketReady;

    public MinioObjectStore(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, Path source, String contentType) throws Exception {
        ensureBucket();
        long size = Files.size(source);
        try (InputStream in = Files.newInputStream(source)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, size, -1)
                    .contentType(contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType)
                    .build());
        }
    }

    @Override
    public InputStream open(String key) throws Exception {
        ensureBucket();
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    }

    @Override
    public void delete(String key) throws Exception {
        ensureBucket();
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO 桶不存在，已创建：{}", bucket);
            }
            bucketReady = true;
        }
    }
}
