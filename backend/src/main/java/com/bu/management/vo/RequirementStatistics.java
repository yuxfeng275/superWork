package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 需求统计 VO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequirementStatistics {

    /**
     * 总需求数
     */
    private Long totalCount;

    /**
     * 项目需求数
     */
    private Long projectRequirementCount;

    /**
     * 产品需求数
     */
    private Long productRequirementCount;

    /**
     * 待评估数
     */
    private Long pendingEvaluationCount;

    /**
     * 评估中数
     */
    private Long evaluatingCount;

    /**
     * 待设计数
     */
    private Long pendingDesignCount;

    /**
     * 设计中数
     */
    private Long designingCount;

    /**
     * 待确认数
     */
    private Long pendingConfirmationCount;

    /**
     * 开发中数
     */
    private Long developingCount;

    /**
     * 测试中数
     */
    private Long testingCount;

    /**
     * 待上线数
     */
    private Long pendingReleaseCount;

    /**
     * 已上线数
     */
    private Long releasedCount;

    /**
     * 已交付数
     */
    private Long deliveredCount;

    /**
     * 已验收数
     */
    private Long acceptedCount;

    /**
     * 已拒绝数
     */
    private Long rejectedCount;

    /**
     * 总预估工时
     */
    private BigDecimal totalEstimatedHours;

    /**
     * 总实际工时
     */
    private BigDecimal totalActualHours;
}
