package com.bu.management.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class WorkItemOverviewResponse {
    private List<WorkItemOverviewItem> records = new ArrayList<>();
    private long total;
    private int current = 1;
    private int size = 20;
    private WorkItemOverviewSummary summary = new WorkItemOverviewSummary();
    private WorkItemAnalysis analysis = new WorkItemAnalysis();
    private LocalDateTime lastSyncedAt;
}
