package com.bu.management.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 项目成员请求 DTO
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
public class ProjectMemberRequest {

    /**
     * 项目ID
     */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /**
     * 用户ID
     */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /**
     * 在项目中的角色
     */
    private String role;
}
