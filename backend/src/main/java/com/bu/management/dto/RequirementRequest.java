package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 需求请求 DTO
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
public class RequirementRequest {

    /**
     * 需求标题
     */
    @NotBlank(message = "需求标题不能为空")
    private String title;

    /**
     * 需求描述
     */
    private String description;

    /**
     * 类型：项目需求/产品需求
     */
    @NotBlank(message = "需求类型不能为空")
    private String type;

    /**
     * 所属业务线
     */
    @NotNull(message = "业务线ID不能为空")
    private Long businessLineId;

    /**
     * 所属项目，产品需求可为空
     */
    private Long projectId;

    /**
     * 客户联系人ID，项目需求必填
     */
    private Long customerContactId;

    /**
     * 优先级：高/中/低
     */
    private String priority;

    /**
     * 来源：客户/商务/内部
     */
    private String source;

    /**
     * 客户期望上线时间
     */
    private LocalDate expectedOnlineDate;
}
