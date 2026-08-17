package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeeyonOaMemberOption {
    private String id;
    private String name;
    private String loginName;
    private String departmentName;
    private String email;
    private String mobile;
    private Boolean enabled;
}