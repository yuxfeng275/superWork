package com.bu.management.service.objectstore;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * 对象存储抽象：只关心「一段字节放在哪个键上」。
 *
 * <p>两套实现：{@link LocalObjectStore}（dev / 单机）与 {@link MinioObjectStore}（prod）。
 * 选哪套由配置 {@code ai.file.store} 决定，业务代码只依赖本接口——这样把原文搬进
 * MinIO 不需要改文件空间、事实层与夜间任务的任何逻辑。
 *
 * @author BU Team
 * @since 2026-11-21
 */
public interface ObjectStore {

    /** 写入；同键覆盖（调用方按 sha256 生成键，天然幂等）。 */
    void put(String key, Path source, String contentType) throws Exception;

    /** 读取；调用方负责关闭。 */
    InputStream open(String key) throws Exception;

    /** 删除；键不存在视为成功。 */
    void delete(String key) throws Exception;
}
