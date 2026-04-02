package com.bu.management.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户请求 DTO
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
public class UserRequest {

    /**
     * 用户名
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码（创建时必填，更新时可选）
     */
    private String password;

    /**
     * 真实姓名
     */
    @NotBlank(message = "真实姓名不能为空")
    private String realName;

    /**
     * 角色：BU负责人、项目经理、技术经理、产品经理、研发、测试、UI设计
     */
    @NotBlank(message = "角色不能为空")
    private String role;

    /**
     * 邮箱
     */
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 手机号
     */
    private String phone;

    /**
     * 状态：1=启用，0=禁用
     */
    private Integer status;
}
