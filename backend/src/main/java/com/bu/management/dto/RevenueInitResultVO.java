package com.bu.management.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RevenueInitResultVO {
    private Integer importedProjectCount = 0;
    private Integer costRowCount = 0;
    private Integer manualRowCount = 0;
    private List<String> errors = new ArrayList<>();
}
