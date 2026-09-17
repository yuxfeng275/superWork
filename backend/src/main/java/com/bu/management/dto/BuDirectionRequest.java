package com.bu.management.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class BuDirectionRequest {
    private String code;
    private String name;
    private String objective;
    private Long ownerId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private Integer sortOrder;
    private List<Long> projectIds = new ArrayList<>();
    private List<MilestoneInput> milestones = new ArrayList<>();

    @Data
    public static class MilestoneInput {
        private Long id;
        private String name;
        private LocalDate dueDate;
        private String status;
        private Integer sortOrder;
    }
}
