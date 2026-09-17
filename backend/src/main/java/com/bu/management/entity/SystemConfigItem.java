package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("system_config_item")
public class SystemConfigItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String groupCode;
    private String groupName;
    private String groupDescription;
    private String configKey;
    private String configName;
    private String configDescription;
    private String valueType;
    private String configValue;
    private Integer isSensitive;
    private Integer isRequired;
    private Integer sortOrder;
    private Integer status;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
