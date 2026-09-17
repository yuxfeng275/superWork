package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务概览统计。
 *
 * @author BU Team
 * @since 2026-07-15
 */
@Data
public class TaskOverviewSummary {

    private Long totalCount = 0L;

    private Long pendingCount = 0L;

    private Long inProgressCount = 0L;

    private Long completedCount = 0L;

    private Long testedCount = 0L;

    private Long unassignedCount = 0L;

    private BigDecimal totalEstimatedHours = BigDecimal.ZERO;

    private BigDecimal totalActualHours = BigDecimal.ZERO;

    private Map<String, Long> statusCounts = new LinkedHashMap<>();
}
