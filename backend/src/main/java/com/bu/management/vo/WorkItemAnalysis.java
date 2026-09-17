package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class WorkItemAnalysis {
    private List<WorkItemDistributionItem> statusDistribution = new ArrayList<>();
    private List<WorkItemDistributionItem> projectDistribution = new ArrayList<>();
    private List<WorkItemDistributionItem> ownerDistribution = new ArrayList<>();
    private List<WorkItemDistributionItem> sourceDistribution = new ArrayList<>();
    private List<WorkItemDistributionItem> priorityDistribution = new ArrayList<>();
    private List<WorkItemDistributionItem> overdueProjectDistribution = new ArrayList<>();
    private List<WorkItemDistributionItem> overdueOwnerDistribution = new ArrayList<>();
    private List<WorkItemDistributionItem> overdueAgeDistribution = new ArrayList<>();
    private BigDecimal totalEstimatedHours = BigDecimal.ZERO;
    private BigDecimal totalActualHours = BigDecimal.ZERO;
    private double completionRate;
    private long unassignedCount;
    private long overdueIncompleteCount;
    private long missingDueDateCount;
}
