package com.bu.management.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class YunxiaoExemptionRequest {
    private Long userId;
    private LocalDate workDate;
    private String reason;
}
