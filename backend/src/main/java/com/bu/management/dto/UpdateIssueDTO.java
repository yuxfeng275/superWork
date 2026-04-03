package com.bu.management.dto;

import lombok.Data;

/**
 * 更新事项 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class UpdateIssueDTO {

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
     * 状态：待处理/处理中/已解决/已关闭
     */
    private String status;

    /**
     * 负责人ID
     */
    private Long assigneeId;

    /**
     * 解决方案
     */
    private String resolution;
}
