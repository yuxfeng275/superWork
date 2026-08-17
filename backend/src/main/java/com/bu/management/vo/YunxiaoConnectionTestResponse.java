package com.bu.management.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class YunxiaoConnectionTestResponse {
    private boolean success;
    private String userId;
    private String userName;
    private String email;
    private String message;
    private LocalDateTime testedAt;
}
