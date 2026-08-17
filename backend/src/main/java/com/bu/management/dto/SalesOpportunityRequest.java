package com.bu.management.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class SalesOpportunityRequest {
    private String name;
    private String customer;
    private String type;
    private String status;
    private BigDecimal amount;
    private String owner;
    private String businessLine;
    private String nextFollowUp;
    private Integer probability;
    private LocalDate expectedClose;
    private String source;
    private String note;
}
