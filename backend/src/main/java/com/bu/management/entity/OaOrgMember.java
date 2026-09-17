package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oa_org_member")
public class OaOrgMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String oaId;
    private String name;
    private String loginName;
    private String departmentOaId;
    private String departmentName;
    private String email;
    private String mobile;
    /** 本次同步仍存在=1，否则=0（不物理删除） */
    private Integer enabled;
    private LocalDateTime syncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
