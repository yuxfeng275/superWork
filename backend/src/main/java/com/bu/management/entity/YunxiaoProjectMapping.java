package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yunxiao_project_mapping")
public class YunxiaoProjectMapping {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String yunxiaoProjectId;
    private String workitemTypeId;
    private String category;
    private Integer syncEnabled;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime fullSyncedAt;
    private String lastSyncStatus;
    private String lastSyncError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
