package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 业务线请求 DTO
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
public class BusinessLineRequest {

    /**
     * 业务线名称
     */
    @NotBlank(message = "业务线名称不能为空")
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 状态：1=启用，0=禁用
     */
    private Integer status;
}
