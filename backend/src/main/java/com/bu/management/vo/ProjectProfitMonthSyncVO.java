package com.bu.management.vo;

import com.bu.management.entity.WorktimeSyncLog;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 项目利润月度一键同步结果：工时明细+成本分析重拉 → 业务线利润镜像刷新 → 当月逐业务线对齐。
 */
@Data
public class ProjectProfitMonthSyncVO {

    private String yearMonth;
    /** 该月已完结：工时/成本重拉被跳过，仅刷新利润镜像 */
    private Boolean monthClosed;
    /** worklog / cost / bl_profit 各步骤日志（含失败原因） */
    private List<WorktimeSyncLog> logs;
    /** 每条 managed 业务线的当月对齐结果 */
    private List<LineAlignment> lines;

    @Data
    public static class LineAlignment {
        private Long businessLineId;
        private String businessLineName;
        /** 差额行.营业收入（合计镜像 − Σ明细） */
        private BigDecimal revenueResidual;
        /** 差额行.成本(人工) */
        private BigDecimal costResidual;
        /** 差额行.工时 */
        private BigDecimal hoursResidual;
        /** |revenue|<0.01 且 |cost|<0.01 且 |hours|<0.0001（null 按 0） */
        private Boolean aligned;
    }
}
