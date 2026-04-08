package com.bu.management.dto;

import lombok.Data;
import java.util.List;

@Data
public class RolePermissionAssignRequest {
    private Long roleId;
    private List<Long> permissionIds;
}
