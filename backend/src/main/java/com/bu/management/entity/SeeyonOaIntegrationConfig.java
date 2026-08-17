package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seeyon_oa_integration_config")
public class SeeyonOaIntegrationConfig {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Integer enabled;
    private String baseUrl;
    private String encryptedUsername;
    private String encryptedPassword;
    private String encryptedToken;
    private Long updatedBy;
    private LocalDateTime lastTestedAt;
    private String lastTestStatus;
    private String lastTestMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}