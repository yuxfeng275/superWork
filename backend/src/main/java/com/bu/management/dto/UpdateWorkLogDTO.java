package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 更新工时记录 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class UpdateWorkLogDTO {

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
