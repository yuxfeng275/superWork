package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("email_integration_config")
public class EmailIntegrationConfig {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Integer deepSeekEnabled;
    private String deepSeekBaseUrl;
    private String deepSeekModel;
    private String encryptedDeepSeekApiKey;
    private String deepSeekTestStatus;
    private String deepSeekTestMessage;
    private LocalDateTime deepSeekTestedAt;
    private Integer weComEnabled;
    private String weComBaseUrl;
    private String weComCorpId;
    private String weComAgentId;
    private String encryptedWeComSecret;
    private String publicBaseUrl;
    private String weComTestStatus;
    private String weComTestMessage;
    private LocalDateTime weComTestedAt;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
