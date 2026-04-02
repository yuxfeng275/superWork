package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 需求状态流转记录 VO
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequirementStatusTransition {

    /**
     * 需求ID
     */
    private Long requirementId;

    /**
     * 需求编号
     */
    private String reqNo;

    /**
     * 需求标题
     */
    private String title;

    /**
     * 当前状态
     */
    private String currentStatus;

    /**
     * 类型
     */
    private String type;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 是否有评估记录
     */
    private Boolean hasEvaluation;

    /**
     * 评估时间
     */
    private LocalDateTime evaluatedAt;

    /**
     * 是否有决策
     */
    private Boolean hasDecision;

    /**
     * 决策结果
     */
    private String decision;

    /**
     * 决策时间
     */
    private LocalDateTime decisionAt;
}
