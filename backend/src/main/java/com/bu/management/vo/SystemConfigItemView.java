package com.bu.management.vo;

public record SystemConfigItemView(
        String key,
        String name,
        String description,
        String valueType,
        String value,
        boolean sensitive,
        boolean configured,
        boolean required,
        int sortOrder) {
}
