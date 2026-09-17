package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yunxiao_workitem_link")
public class YunxiaoWorkitemLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long requirementId;
    private Long projectId;
    private String yunxiaoWorkitemId;
    private String serialNumber;
    private String syncStatus;
    private String lastError;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
