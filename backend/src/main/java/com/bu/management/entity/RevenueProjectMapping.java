package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("revenue_project_mapping")
public class RevenueProjectMapping {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceType;
    private String sourceName;
    private Long projectId;
    private Long businessLineId;
    private String category;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
