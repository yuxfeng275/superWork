package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 任务统计 VO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatistics {

    /**
     * 总任务数
     */
    private Long totalCount;

    /**
     * 待开始数
     */
    private Long pendingCount;

    /**
     * 进行中数
     */
    private Long inProgressCount;

    /**
     * 已完成数
     */
    private Long completedCount;

    /**
     * 高优先级数
     */
    private Long highPriorityCount;

    /**
     * 中优先级数
     */
    private Long mediumPriorityCount;

    /**
     * 低优先级数
     */
    private Long lowPriorityCount;

    /**
     * 总预估工时
     */
    private BigDecimal totalEstimatedHours;

    /**
     * 总实际工时
     */
    private BigDecimal totalActualHours;

    /**
     * 工时完成率
     */
    private BigDecimal hoursCompletionRate;
}
