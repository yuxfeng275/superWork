package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建设计工作记录 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class CreateDesignWorkLogDTO {

    /**
     * 需求ID
     */
    private Long requirementId;

    /**
     * 工作类型：原型设计/UI设计/技术方案设计
     */
    private String workType;

    /**
     * 设计人ID
     */
    private Long designerId;

    /**
     * 预估工时
     */
    private BigDecimal estimatedHours;

    /**
     * 工作内容描述
     */
    private String workContent;

    /**
     * 计划完成时间
     */
    private LocalDateTime plannedCompletedAt;

    /**
     * 状态：待开始/进行中/已完成
     */
    private String status;
}
