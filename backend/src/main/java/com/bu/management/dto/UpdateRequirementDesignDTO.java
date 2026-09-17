package com.bu.management.dto;

import lombok.Data;

/**
 * 更新需求设计 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class UpdateRequirementDesignDTO {

    /**
     * 原型设计状态：未开始/进行中/已完成
     */
    private String prototypeStatus;

    /**
     * UI设计状态：未开始/进行中/已完成
     */
    private String uiStatus;

    /**
     * 技术方案状态：未开始/进行中/已完成
     */
    private String techSolutionStatus;
}
