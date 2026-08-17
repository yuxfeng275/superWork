package com.bu.management.dto;

import lombok.Data;

@Data
public class BuKeyMatterWeeklyUpdateRequest {
    private String status;
    private Integer progress;
    private String progressSummary;
    private String issues;
    private String nextWeekPlan;
    private String supportNeeded;
}
