package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class KpiReportVO {
    private Integer year;
    private List<GroupRow> groups;
    private GroupTotal total;

    @Data
    public static class GroupRow {
        private String reportGroup;
        private BigDecimal revenueTarget;
        private BigDecimal profitTarget;
        /** 最新快照（含环比增量与备注） */
        private SnapshotRow latest;
        /** 全年快照（按周升序），前端据此组装月度列 */
        private List<SnapshotRow> snapshots;
    }

    @Data
    public static class SnapshotRow {
        private Long snapshotId;
        private LocalDate weekEndDate;
        private BigDecimal ytdRevenue;
        private BigDecimal ytdDirectCost;
        private BigDecimal ytdLaborCost;
        private BigDecimal ytdOtherCost;
        private BigDecimal ytdProfit;
        private BigDecimal weekDeltaRevenue;
        private BigDecimal weekDeltaProfit;
        private Integer estimated;
        /** 达成率：ytdRevenue / revenueTarget */
        private BigDecimal revenueRate;
        private BigDecimal profitRate;
        private List<NoteRow> notes;
    }

    @Data
    public static class NoteRow {
        private Long id;
        private String metric;
        private String alertLevel;
        private String deviationReason;
        private Integer isAbnormal;
        private String countermeasure;
        private String status;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class GroupTotal {
        private BigDecimal revenueTarget;
        private BigDecimal profitTarget;
        private BigDecimal ytdRevenue;
        private BigDecimal ytdProfit;
        private BigDecimal weekDeltaRevenue;
        private BigDecimal weekDeltaProfit;
        private BigDecimal revenueRate;
        private BigDecimal profitRate;
    }
}
