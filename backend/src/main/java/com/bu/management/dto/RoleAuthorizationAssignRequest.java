package com.bu.management.dto;

import lombok.Data;

import java.util.List;

@Data
public class RoleAuthorizationAssignRequest {
    private Long roleId;
    private List<Long> menuIds;
    private List<Long> permissionIds;
    private String dataScope;
    private String dataScopeValue;
}
