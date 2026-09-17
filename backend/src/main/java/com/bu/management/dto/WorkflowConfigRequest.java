package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class WorkflowConfigRequest {

    @NotBlank(message = "需求类型不能为空")
    private String requirementType;

    @NotBlank(message = "当前状态不能为空")
    private String fromStatus;

    @NotBlank(message = "目标状态不能为空")
    private String toStatus;

    @NotEmpty(message = "允许角色不能为空")
    private List<String> allowedRoles;

    private String conditionType;

    @NotNull(message = "启用状态不能为空")
    private Integer isActive;

    @NotNull(message = "排序不能为空")
    private Integer sortOrder;
}
