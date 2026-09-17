package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("bu_direction")
public class BuDirection {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String objective;
    private Long ownerId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private Integer sortOrder;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
