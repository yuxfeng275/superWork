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
     * 角色：总监/副总监/经营负责人/成效负责人/解决方案经理/技术架构师/全栈工程师/质量工程师/智能运营工程师/智能客服专员/体验与内容设计师
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
