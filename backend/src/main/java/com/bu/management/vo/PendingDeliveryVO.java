package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 待交付合同确认视图：当年 delivery_date 为空的合同明细，按预计交付月份（service_end_date 所在月）分组。
 * 月份早于当月 = 已逾期（overdue）；service_end_date 为空 = 未定月份（unknown，不进月份预估块）。
 * 金额为合同应收（含税，元）。
 */
@Data
public class PendingDeliveryVO {
    private Integer year;
    /** 当月 YYYY-MM（逾期/未定的判定基准） */
    private String currentMonth;
    private List<MonthGroup> groups;

    @Data
    public static class MonthGroup {
        /** 预计交付月份 YYYY-MM；null=未定月份（服务结束时间缺失） */
        private String month;
        /** 展示标签，如 2026年10月 / 未定月份 */
        private String label;
        /** 警告标记：overdue=已逾期；unknown=未定月份；null=正常 */
        private String warning;
        private Integer entryCount;
        private Integer confirmedCount;
        /** 含税合计（元） */
        private BigDecimal totalAmount;
        /** 已确认部分含税合计（元） */
        private BigDecimal confirmedAmount;
        private List<Entry> entries;
    }

    @Data
    public static class Entry {
        /** revenue_contract_entry.id（确认/取消的操作主键） */
        private Long id;
        private String contractNo;
        private String contractName;
        private String customer;
        private String itemDesc;
        /** 应收金额（含税，元） */
        private BigDecimal receivableAmount;
        /** 提交合同的人 = 工时系统承接人（缺省报价人） */
        private String salesOwner;
        private LocalDate serviceEndDate;
        /** 预计交付月份 YYYY-MM；null=未定 */
        private String expectedMonth;
        private String businessLineName;
        private String projectName;
        /** 预计交付月份早于当月 */
        private Boolean overdue;
        /** 当前是否已确认 */
        private Boolean confirmed;
        private String confirmedByName;
        private LocalDateTime confirmedAt;
    }
}
