package com.bu.management.dto;

import lombok.Data;

@Data
public class RevenueImportResultVO {
    private Long batchId;
    private Integer totalCount = 0;
    private Integer successCount = 0;
    /** 待映射行数（业务线或项目未匹配） */
    private Integer pendingCount = 0;
}
