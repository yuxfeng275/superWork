package com.bu.management.vo;

import java.util.List;

public record SystemConfigGroupView(
        String groupCode,
        String groupName,
        String description,
        List<SystemConfigItemView> items) {
}
