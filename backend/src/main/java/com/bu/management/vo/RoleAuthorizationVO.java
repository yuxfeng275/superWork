package com.bu.management.vo;

import lombok.Data;

import java.util.List;

@Data
public class RoleAuthorizationVO {
    private List<Long> menuIds;
    private List<Long> permissionIds;
    private String dataScope;
    private String dataScopeValue;
}
