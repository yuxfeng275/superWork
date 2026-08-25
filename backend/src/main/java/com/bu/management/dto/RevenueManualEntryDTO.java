package com.bu.management.dto;

import lombok.Data;

@Data
public class RevenueManualEntryDTO {
    private Long id;
    private String yearMonth;
    private Long projectId;
    private Long businessLineId;
    private String entryType;
    private Long amount;
    private String remark;
}
