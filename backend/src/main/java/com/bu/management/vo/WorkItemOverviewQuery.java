package com.bu.management.vo;

import lombok.Data;

@Data
public class WorkItemOverviewQuery {
    private int page = 1;
    private int size = 20;
    private Long businessLineId;
    private Long projectId;
    private Long assigneeId;
    private String dataSource;
    private String normalizedStatus;
    private String type;
    private String priority;
    private String keyword;
}
