package com.bu.management.dto;

import lombok.Data;

@Data
public class YunxiaoProjectMappingRequest {
    private Long projectId;
    private String yunxiaoProjectId;
    private String workitemTypeId;
    private String category = "Req";
    private Integer syncEnabled = 1;
}
