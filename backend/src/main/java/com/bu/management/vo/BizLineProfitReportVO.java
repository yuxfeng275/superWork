package com.bu.management.vo;

import com.bu.management.entity.BizLineProfitReport;
import lombok.Data;

import java.util.List;

/**
 * 业务线利润年度报表：业务线分组（月行 + YTD 小计）+ 全表总计。
 */
@Data
public class BizLineProfitReportVO {

    private Integer year;
    private List<LineGroup> lines;
    /** 全业务线 YTD 总计（yearMonth 为 null） */
    private BizLineProfitReport totalYtd;

    @Data
    public static class LineGroup {
        private Long businessLineId;
        /** 业务线名称（工时系统口径） */
        private String businessLineName;
        private String groupName;
        /** 月度行（按 yearMonth 升序） */
        private List<BizLineProfitReport> months;
        /** 该业务线 YTD 小计（yearMonth = "YTD"） */
        private BizLineProfitReport ytd;
    }
}
