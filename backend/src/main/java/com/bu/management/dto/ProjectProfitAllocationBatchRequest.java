package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 项目利润分配批量保存请求：按 (yearMonth, targetType, projectId, costType) 唯一键 upsert。
 * 金额单位元、工时单位人月，允许负数作调整项；差额所有项（9 列）均可分配。
 */
@Data
public class ProjectProfitAllocationBatchRequest {

    /** YYYY-MM */
    private String yearMonth;
    private Long businessLineId;
    /** 分配目标列表：项目行/项目集/精准单行（project）、销售行（sales）、业务线行（line_other） */
    private List<Target> targets;

    @Data
    public static class Target {
        /** 目标所在业务线：null=请求业务线；full 线（云鹿Saas/定制）之间允许互相跨线分配 */
        private Long businessLineId;
        /** project/sales/line_other */
        private String targetType;
        /** 主项目ID；非项目目标（销售/业务线/项目集/精准单行）传 0 */
        private Long projectId;
        private List<Item> items;
    }

    @Data
    public static class Item {
        /** revenue/sms/direct/platform_fee/compensation/outsourcing/software_gift/hours/cost */
        private String costType;
        private BigDecimal amount;
        private String note;
    }
}
