package com.bu.management.dto;

import lombok.Data;
import java.util.List;

@Data
public class RoleMenuAssignRequest {
    private Long roleId;
    private List<Long> menuIds;
}
