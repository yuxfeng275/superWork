package com.bu.management.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目成员 VO（包含用户和项目信息）
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
public class ProjectMemberVO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 真实姓名
     */
    private String realName;

    /**
     * 在项目中的角色
     */
    private String role;

    /**
     * 加入时间
     */
    private LocalDateTime joinedAt;
}
