package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目成员实体
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
@TableName("project_member")
public class ProjectMember {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 项目ID
     */
    private Long projectId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 在项目中的角色
     */
    private String role;

    /**
     * 加入时间
     */
    private LocalDateTime joinedAt;
}
