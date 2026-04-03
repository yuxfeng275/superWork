package com.bu.management.dto;

import lombok.Data;

/**
 * 创建事项 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class CreateIssueDTO {

    /**
     * 关联类型：需求/任务
     */
    private String relatedType;

    /**
     * 关联ID
     */
    private Long relatedId;

    /**
     * 事项标题
     */
    private String title;

    /**
     * 事项描述
     */
    private String description;

    /**
     * 事项类型：问题/风险/变更
     */
    private String issueType;

    /**
     * 严重程度：低/中/高/紧急
     */
    private String severity;

    /**
     * 负责人ID
     */
    private Long assigneeId;

    /**
     * 创建人ID
     */
    private Long createdBy;
}
