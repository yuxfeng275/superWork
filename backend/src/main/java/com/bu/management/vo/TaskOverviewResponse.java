package com.bu.management.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务概览响应。
 *
 * @author BU Team
 * @since 2026-07-15
 */
@Data
public class TaskOverviewResponse {

    private TaskOverviewSummary summary = new TaskOverviewSummary();

    private WorkItemAnalysis analysis = new WorkItemAnalysis();

    private List<TaskOverviewItem> tasks = new ArrayList<>();
}
