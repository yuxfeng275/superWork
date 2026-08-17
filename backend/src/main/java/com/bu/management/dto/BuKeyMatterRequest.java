package com.bu.management.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class BuKeyMatterRequest {
    private String title;
    private String description;
    private Long projectId;
    private Long ownerId;
    private String priority;
    private String status;
    private Integer progress;
    private LocalDate startDate;
    private LocalDate plannedCompletionDate;
    private Integer sortOrder;
}
