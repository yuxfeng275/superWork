package com.bu.management.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class BuKeyMatterWeeklyUpdateView {
    private Long id;
    private LocalDate weekStartDate;
    private String status;
    private Integer progress;
    private String progressSummary;
    private String issues;
    private String nextWeekPlan;
    private String supportNeeded;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
