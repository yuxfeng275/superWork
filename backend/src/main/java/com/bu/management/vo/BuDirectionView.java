package com.bu.management.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class BuDirectionView {
    private Long id;
    private String code;
    private String name;
    private String objective;
    private Long ownerId;
    private String ownerName;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private Integer sortOrder;
    private BigDecimal progress;
    private String health;
    private Long requirementCount;
    private Long completedRequirementCount;
    private Long taskCount;
    private Long completedTaskCount;
    private List<Long> projectIds = new ArrayList<>();
    private List<String> projectNames = new ArrayList<>();
    private List<MilestoneView> milestones = new ArrayList<>();

    @Data
    public static class MilestoneView {
        private Long id;
        private String name;
        private LocalDate dueDate;
        private String status;
        private LocalDateTime completedAt;
        private Integer sortOrder;
        private boolean overdue;
    }
}
