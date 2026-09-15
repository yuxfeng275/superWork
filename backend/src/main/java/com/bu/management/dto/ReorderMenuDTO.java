package com.bu.management.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ReorderMenuDTO {
    private Long parentId;

    @NotEmpty(message = "菜单排序列表不能为空")
    private List<@NotNull(message = "菜单ID不能为空") Long> menuIds;
}
