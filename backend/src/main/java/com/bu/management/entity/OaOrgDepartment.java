package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("oa_org_department")
public class OaOrgDepartment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String oaId;
    private String name;
    private String parentOaId;
    private String parentName;
    private Integer sortOrder;
    /** 本次同步仍存在=1，否则=0（不物理删除） */
    private Integer enabled;
    private LocalDateTime syncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
