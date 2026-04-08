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
     * 预估工时
     */
    private BigDecimal estimatedHours;
}
