package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateRoleDTO {
    @NotBlank(message = "角色编码不能为空")
    private String code;

    @NotBlank(message = "角色名称不能为空")
    private String name;

    private String description;
    private Integer status;
    private String dataScope;
    private String dataScopeValue;
}
