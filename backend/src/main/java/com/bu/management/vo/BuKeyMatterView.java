package com.bu.management.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class BuKeyMatterView {
    private Long id;
    private String title;
    private String description;
    private Long projectId;
    private String projectName;
    private Long projectRootId;
    private String projectRootName;
    private Long ownerId;
    private String ownerName;
    private String priority;
    private String status;
    private Integer progress;
    private LocalDate startDate;
    private LocalDate plannedCompletionDate;
    private LocalDateTime completedAt;
    private Integer sortOrder;
    private boolean overdue;
    private boolean currentWeekUpdated;
    private List<BuKeyMatterParticipantView> participants = new ArrayList<>();
    private BuKeyMatterWeeklyUpdateView latestUpdate;
    private BuKeyMatterWeeklyUpdateView currentWeekUpdate;
    private List<BuKeyMatterWeeklyUpdateView> weeklyUpdates = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
