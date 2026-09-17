package com.bu.management.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 客户联系人请求 DTO
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
public class CustomerContactRequest {

    /**
     * 所属项目
     */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /**
     * 联系人姓名
     */
    @NotBlank(message = "联系人姓名不能为空")
    private String name;

    /**
     * 公司名称
     */
    private String company;

    /**
     * 职位
     */
    private String position;

    /**
     * 电话
     */
    private String phone;

    /**
     * 邮箱
     */
    @Email(message = "邮箱格式不正确")
    private String email;

    /**
     * 是否有效：1=是，0=否
     */
    private Integer isActive;
}
