package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkItemDistributionItem {
    private String key;
    private String label;
    private long count;
    private double percentage;
}
