package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营收合同导入批次历史
 */
@Data
@TableName("revenue_contract_import_batch")
public class RevenueContractImportBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fileName;
    private Integer totalCount;
    private Integer successCount;
    private Integer pendingCount;
    private Long createdBy;
    private LocalDateTime createdAt;
}
