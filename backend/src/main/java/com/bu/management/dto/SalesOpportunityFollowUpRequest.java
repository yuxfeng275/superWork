package com.bu.management.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SalesOpportunityFollowUpRequest {
    private LocalDateTime followUpAt;
    private String follower;
    private String content;
    private String status;
    private Integer probability;
    private String nextFollowUp;
}
