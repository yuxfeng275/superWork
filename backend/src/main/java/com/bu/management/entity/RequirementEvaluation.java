package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 需求评估实体
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
@TableName("requirement_evaluation")
public class RequirementEvaluation {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 需求ID
     */
    private Long requirementId;

    /**
     * 技术可行性：1=是，0=否
     */
    private Integer isFeasible;

    /**
     * 可行性说明
     */
    private String feasibilityDesc;

    /**
     * 预估工作量（人天）
     */
    private BigDecimal estimatedWorkload;

    /**
     * 预估报价（元），项目需求必填
     */
    private BigDecimal estimatedCost;

    /**
     * 工作内容拆解（JSON）
     */
    private String workBreakdown;

    /**
     * 是否建议转产品需求：1=是，0=否
     */
    private Integer suggestProduct;

    /**
     * 评估人
     */
    private Long evaluatorId;

    /**
     * 评估时间
     */
    private LocalDateTime evaluatedAt;

    /**
     * BU决策：通过/拒绝/转产品需求
     */
    private String decision;

    /**
     * 决策人
     */
    private Long decisionBy;

    /**
     * 决策时间
     */
    private LocalDateTime decisionAt;

    /**
     * 决策理由
     */
    private String decisionReason;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
