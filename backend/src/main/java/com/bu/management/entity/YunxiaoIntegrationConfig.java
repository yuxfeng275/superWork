package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yunxiao_integration_config")
public class YunxiaoIntegrationConfig {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Integer enabled;
    private String edition;
    private String baseUrl;
    private String organizationId;
    private String encryptedToken;
    private Long updatedBy;
    private LocalDateTime lastTestedAt;
    private String lastTestStatus;
    private String lastTestMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
