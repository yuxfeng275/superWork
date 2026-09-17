package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营收导入批次
 */
@Data
@TableName("revenue_import_batch")
public class RevenueImportBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** worklog/cost */
    private String importType;
    @TableField("`year_month`")
    private String yearMonth;
    private String fileName;
    private Integer totalCount;
    private Integer successCount;
    private Integer pendingCount;
    private Long createdBy;
    private LocalDateTime createdAt;
}
