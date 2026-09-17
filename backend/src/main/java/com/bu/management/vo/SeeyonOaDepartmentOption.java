package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeeyonOaDepartmentOption {
    private String id;
    private String name;
    private String parentId;
    private String parentName;
    private Integer sortOrder;
    private Boolean enabled;
}