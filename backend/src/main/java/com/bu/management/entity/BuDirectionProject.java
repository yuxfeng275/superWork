package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bu_direction_project")
public class BuDirectionProject {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long directionId;
    private Long projectId;
    private LocalDateTime createdAt;
}
