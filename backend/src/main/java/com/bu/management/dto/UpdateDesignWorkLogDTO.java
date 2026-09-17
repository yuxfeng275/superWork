package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 更新设计工作记录 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class UpdateDesignWorkLogDTO {

    /**
     * 实际工时
     */
    private BigDecimal actualHours;

    /**
     * 成果物链接或文件ID
     */
    private String resultUrl;

    /**
     * 工作内容描述
     */
    private String workContent;

    /**
     * 设计人ID
     */
    private Long designerId;

    /**
     * 预估工时
     */
    private BigDecimal estimatedHours;

    /**
     * 计划完成时间
     */
    private LocalDateTime plannedCompletedAt;

    /**
     * 状态：待开始/进行中/已完成
     */
    private String status;
}
