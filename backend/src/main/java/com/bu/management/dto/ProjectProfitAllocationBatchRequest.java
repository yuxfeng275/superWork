package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 项目利润成本分配批量保存请求：按 (yearMonth, projectId, costType) 唯一键 upsert。
 * 金额单位元，允许负数作调整项。
 */
@Data
public class ProjectProfitAllocationBatchRequest {

    /** YYYY-MM */
    private String yearMonth;
    private Long businessLineId;
    /** 主项目ID */
    private Long projectId;
    private List<Item> items;

    @Data
    public static class Item {
        /** sms/direct/platform_fee/compensation/outsourcing/software_gift */
        private String costType;
        private BigDecimal amount;
        private String note;
    }
}
