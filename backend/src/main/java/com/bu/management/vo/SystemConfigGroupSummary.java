package com.bu.management.vo;

public record SystemConfigGroupSummary(
        String groupCode,
        String groupName,
        String description,
        int itemCount,
        int configuredCount) {
}
