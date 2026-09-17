package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class WorkItemOverviewItem {
    private String recordKey;
    private String dataSource;
    private boolean readOnly;
    private Long id;
    private String yunxiaoWorkitemId;
    private String serialNumber;
    private String category;
    private String title;
    private String description;
    private List<Long> projectIds = new ArrayList<>();
    private List<String> projectNames = new ArrayList<>();
    private Long projectId;
    private String projectName;
    private String projectFullPath;
    private Long assigneeId;
    private String assigneeKey;
    private String assigneeName;
    private String assigneeUsername;
    private String status;
    private String normalizedStatus;
    private BigDecimal estimatedHours;
    private BigDecimal actualHours;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastSyncedAt;
    private LocalDate dueDate;
    private boolean overdueIncomplete;
    private Long overdueDays;

    private String requirementNo;
    private Long requirementId;
    private String requirementTitle;
    private Long businessLineId;
    private String type;
    private String priority;
    private String businessSource;
    private Long customerContactId;
    private Long creatorId;
    private LocalDate expectedOnlineDate;
    private LocalDate estimatedOnlineDate;
    private LocalDate actualOnlineDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private String taskType;

    private String linkedYunxiaoWorkitemId;
    private String linkedYunxiaoSerialNumber;
    private String linkedYunxiaoStatus;
    private LocalDateTime linkedYunxiaoLastSyncedAt;
}
