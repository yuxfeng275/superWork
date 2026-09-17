package com.bu.management.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class SalesOpportunitySupportWorkLogRequest {
    private LocalDate supportDate;
    private String supporter;
    private BigDecimal hours;
    private String supportType;
    private String content;
}
