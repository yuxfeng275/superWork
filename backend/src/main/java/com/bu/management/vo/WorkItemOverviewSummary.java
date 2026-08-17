package com.bu.management.vo;

import lombok.Data;

@Data
public class WorkItemOverviewSummary {
    private long totalCount;
    private long localCount;
    private long yunxiaoCount;
    private long pendingCount;
    private long inProgressCount;
    private long completedCount;
    private long otherCount;
}
