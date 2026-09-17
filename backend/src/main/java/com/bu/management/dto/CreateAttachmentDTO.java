package com.bu.management.dto;

import lombok.Data;

/**
 * 创建附件 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class CreateAttachmentDTO {

    /**
     * 关联类型：需求/任务/事项/设计工作记录
     */
    private String relatedType;

    /**
     * 关联ID
     */
    private Long relatedId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件类型
     */
    private String fileType;

    /**
     * 上传人ID
     */
    private Long uploadedBy;
}
