package com.bu.management.dto;

import lombok.Data;

@Data
public class YunxiaoUserMappingRequest {
    private Long userId;
    private String yunxiaoUserId;
    private Integer syncEnabled = 1;
}
