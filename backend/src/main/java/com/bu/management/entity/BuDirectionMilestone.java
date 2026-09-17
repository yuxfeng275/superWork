package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("bu_direction_milestone")
public class BuDirectionMilestone {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long directionId;
    private String name;
    private LocalDate dueDate;
    private String status;
    private LocalDateTime completedAt;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
