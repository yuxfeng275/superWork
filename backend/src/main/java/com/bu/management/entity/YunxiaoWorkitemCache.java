package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("yunxiao_workitem_cache")
public class YunxiaoWorkitemCache {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String yunxiaoWorkitemId;
    private String yunxiaoProjectId;
    private Long projectId;
    private String serialNumber;
    private String category;
    private String title;
    private String status;
    private String normalizedStatus;
    private String yunxiaoAssigneeId;
    private String assigneeName;
    private BigDecimal estimatedHours;
    private BigDecimal actualHours;
    private String rawJson;
    private LocalDateTime sourceCreatedAt;
    private LocalDateTime sourceUpdatedAt;
    private LocalDate dueDate;
    private Integer active;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
