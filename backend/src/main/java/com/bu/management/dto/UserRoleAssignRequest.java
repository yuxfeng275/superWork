package com.bu.management.dto;

import lombok.Data;
import java.util.List;

@Data
public class UserRoleAssignRequest {
    private Long userId;
    private List<Long> roleIds;
}
