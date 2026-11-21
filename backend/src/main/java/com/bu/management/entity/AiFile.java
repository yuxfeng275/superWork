package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 统一空间的文件元数据；原文在对象存储（prod=MinIO，dev=本地盘）。
 *
 * <p>同一项目内 {@code sha256} 唯一：同一份文件被第二个人再投一次时复用本行，
 * 只有事实层按 {@code file:<id>} 幂等刷新，避免重复存原文与重复进评审。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Data
@TableName("ai_file")
public class AiFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** 投递人；事实层据此判断归属与可见性 */
    private Long ownerUserId;

    private String filename;

    private String contentType;

    private Long sizeBytes;

    /** 内容哈希（hex 小写） */
    private String sha256;

    /** 对象存储键：{@code <projectId>/<sha 前 2 位>/<sha>.<ext>} */
    private String objectKey;

    /** UPLOAD / EMAIL / SYNC_DIR / AGENT */
    private String source;

    /** 邮件 id、目录路径或 run 号 */
    private String sourceRef;

    /** PROJECT / PRIVATE_COMPANY / OWNER */
    private String visibility;

    private LocalDateTime deletedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
