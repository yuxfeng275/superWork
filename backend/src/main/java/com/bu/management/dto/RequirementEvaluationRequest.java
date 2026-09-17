package com.bu.management.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 需求评估请求 DTO
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
public class RequirementEvaluationRequest {

    /**
     * 需求ID
     */
    @NotNull(message = "需求ID不能为空")
    private Long requirementId;

    /**
     * 技术可行性：1=是，0=否
     */
    @NotNull(message = "技术可行性不能为空")
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
     * 工作内容拆解（JSON字符串）
     */
    private String workBreakdown;

    /**
     * 是否建议转产品需求：1=是，0=否
     */
    private Integer suggestProduct;
}
