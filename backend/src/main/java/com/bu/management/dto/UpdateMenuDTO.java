package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateMenuDTO {
    @NotBlank(message = "菜单名称不能为空")
    @Size(max = 50, message = "菜单名称不能超过50个字符")
    private String name;

    private Long parentId;

    @Size(max = 100, message = "菜单图标不能超过100个字符")
    private String icon;

    @Size(max = 200, message = "菜单路径不能超过200个字符")
    private String path;

    @Size(max = 200, message = "组件路径不能超过200个字符")
    private String component;

    private Integer sortOrder;
    private Integer visible;
    private Integer status;
}
