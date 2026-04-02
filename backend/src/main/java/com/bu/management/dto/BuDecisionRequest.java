package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * BU决策请求 DTO
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
public class BuDecisionRequest {

    /**
     * 需求ID
     */
    @NotNull(message = "需求ID不能为空")
    private Long requirementId;

    /**
     * 决策：通过/拒绝/转产品需求
     */
    @NotBlank(message = "决策不能为空")
    private String decision;

    /**
     * 决策理由
     */
    private String decisionReason;
}
