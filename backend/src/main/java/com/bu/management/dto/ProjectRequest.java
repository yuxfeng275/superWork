package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 项目请求 DTO
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
public class ProjectRequest {

    /**
     * 所属业务线
     */
    @NotNull(message = "业务线ID不能为空")
    private Long businessLineId;

    /**
     * 父项目ID，NULL表示主项目
     */
    private Long parentId;

    /**
     * 项目名称
     */
    @NotBlank(message = "项目名称不能为空")
    private String name;

    /**
     * 项目编码
     */
    private String code;

    /**
     * 项目经理ID
     */
    private Long managerId;

    /**
     * 状态：1=进行中，0=已结束
     */
    private Integer status;
}
