package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新任务 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class UpdateTaskDTO {

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

    /**
     * 实际工时
     */
    private BigDecimal actualHours;

    /**
     * 状态：待开始/进行中/已完成
     */
    private String status;

    /**
     * 优先级：低/中/高
     */
    private String priority;
}
