package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 创建工时记录 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class CreateWorkLogDTO {

    /**
     * 任务ID
     */
    private Long taskId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 工作日期
     */
    private LocalDate workDate;

    /**
     * 工时（小时）
     */
    private BigDecimal hours;

    /**
     * 工作内容描述
     */
    private String workContent;
}
