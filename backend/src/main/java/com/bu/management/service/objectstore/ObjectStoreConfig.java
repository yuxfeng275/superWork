package com.bu.management.service.objectstore;

import io.minio.MinioClient;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配：{@code ai.file.store} 取 {@code minio} 用 MinIO，其余（默认 local）用本地盘。
 *
 * <p>为什么不做成条件 Bean 两套配置：夜间任务读写文件的代码只有一份，
 * 用哪个存储是部署问题，不是业务分支。切换只需要改一行配置并重启。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Configuration
public class ObjectStoreConfig {

    @Bean
    public ObjectStore objectStore(@Value("${ai.file.store:local}") String store,
                                   @Value("${ai.file.local-root:/data/ai-files}") String localRoot,
                                   @Value("${minio.endpoint:}") String endpoint,
                                   @Value("${minio.access-key:}") String accessKey,
                                   @Value("${minio.secret-key:}") String secretKey,
                                   @Value("${minio.bucket-name:bu-management}") String bucket) {
        if ("minio".equalsIgnoreCase(store)) {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("ai.file.store=minio 但 minio.endpoint 为空");
            }
            log.info("文件空间使用 MinIO：{} bucket={}", endpoint, bucket);
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            return new MinioObjectStore(client, bucket);
        }
        log.info("文件空间使用本地盘：{}", localRoot);
        return new LocalObjectStore(Path.of(localRoot));
    }
}
