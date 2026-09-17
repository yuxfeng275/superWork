package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建任务 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class CreateTaskDTO {

    /**
     * 需求ID
     */
    private Long requirementId;

    /**
     * 任务标题
     */
    private String title;

    /**
     * 任务描述
     */
    private String description;

    /**
     * 负责人ID
     */
    private Long assigneeId;

    /**
     * 任务类型：前端开发/后端开发/测试/UI设计/开发任务
     */
    private String taskType;

    /**
     * 创建人ID
     */
    private Long createdBy;

    /**
     * 预估工时
     */
    private BigDecimal estimatedHours;
}
