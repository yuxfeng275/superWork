package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("workflow_config")
public class WorkflowConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requirementType;

    private String fromStatus;

    private String toStatus;

    private String allowedRoles;

    private String conditionType;

    private Integer isActive;

    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
