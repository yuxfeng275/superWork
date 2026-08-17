package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class BuDashboardResponse {
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Integer planWindowWorkdays;
    private Summary summary = new Summary();
    private List<BuDirectionView> directions = new ArrayList<>();
    private List<CapacityItem> capacity = new ArrayList<>();
    private List<WorklogItem> worklogs = new ArrayList<>();
    private IntegrationStatus integration = new IntegrationStatus();

    @Data
    public static class Summary {
        private long directionCount;
        private long atRiskDirectionCount;
        private long activeRequirementCount;
        private long overdueTaskCount;
        private long overloadedPeopleCount;
        private long missingWorklogPeopleCount;
    }

    @Data
    public static class CapacityItem {
        private Long userId;
        private String realName;
        private String role;
        private long activeTaskCount;
        private long overdueTaskCount;
        private BigDecimal actualHours;
        private BigDecimal expectedHours;
        private BigDecimal actualEffortRate;
        private BigDecimal plannedHours;
        private BigDecimal plannedLoadRate;
        private String loadStatus;
        private String dataCompleteness;
        private boolean yunxiaoMapped;
        private List<String> activeWork = new ArrayList<>();
    }

    @Data
    public static class WorklogItem {
        private Long userId;
        private String realName;
        private String role;
        private LocalDate workDate;
        private BigDecimal expectedHours;
        private BigDecimal actualHours;
        private String status;
        private boolean finalResult;
    }

    @Data
    public static class IntegrationStatus {
        private boolean enabled;
        private boolean configured;
        private String edition;
        private String baseUrl;
        private String organizationId;
        private boolean tokenConfigured;
        private String tokenSource;
        private boolean organizationConfigured;
        private long mappedProjects;
        private long mappedUsers;
        private LocalDateTime lastTestedAt;
        private String lastTestStatus;
        private String lastTestMessage;
        private LocalDateTime lastSuccessfulSync;
        private String lastError;
    }
}
