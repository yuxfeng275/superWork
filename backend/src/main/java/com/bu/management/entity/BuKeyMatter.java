package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
    private String description;
    private Long projectId;
    private Long ownerId;
    private String priority;
    private String status;
    private Integer progress;
    private LocalDate startDate;
    private LocalDate plannedCompletionDate;
    private LocalDateTime completedAt;
    private Integer sortOrder;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
