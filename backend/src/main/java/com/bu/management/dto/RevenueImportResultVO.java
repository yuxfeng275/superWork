package com.bu.management.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RevenueImportResultVO {
    private Integer successCount = 0;
    private Integer newMappingCount = 0;
    private Integer pendingMappingCount = 0;
    private List<String> errors = new ArrayList<>();
}
