package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("bu_key_matter")
public class BuKeyMatter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    /** 可选字段允许清空（默认 NOT_NULL 会把 null 跳过，导致清空不生效）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long projectId;
    private Long ownerId;
    private String priority;
    private String status;
    private Integer progress;
    private LocalDate startDate;
    private LocalDate plannedCompletionDate;
    /** 从「已完成」改回其它状态时必须能写回 NULL，否则事项仍会被当成本周完成。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime completedAt;
    private Integer sortOrder;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
