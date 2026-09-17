package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("yunxiao_estimated_effort")
public class YunxiaoEstimatedEffort {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String yunxiaoRecordId;
    private String yunxiaoWorkitemId;
    private Long projectId;
    private String yunxiaoUserId;
    private String userName;
    private BigDecimal estimatedHours;
    private String workType;
    private String description;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
