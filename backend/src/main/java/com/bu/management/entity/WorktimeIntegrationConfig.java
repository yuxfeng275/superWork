package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("worktime_integration_config")
public class WorktimeIntegrationConfig {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Integer enabled;
    private String baseUrl;
    private String encryptedEmployeeNo;
    private String encryptedPassword;
    private Long updatedBy;
    private LocalDateTime lastTestedAt;
    private String lastTestStatus;
    private String lastTestMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
