package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("revenue_import_record")
public class RevenueImportRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String importType;
    private String fileName;
    private Integer successCount;
    private Integer newMappingCount;
    private Integer pendingMappingCount;
    private Integer errorCount;
    private Long createdBy;
    private LocalDateTime createdAt;
}
