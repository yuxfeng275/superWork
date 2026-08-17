package com.bu.management.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

@Data
public class SystemConfigGroupRequest {
    @NotEmpty(message = "配置内容不能为空")
    private Map<String, String> values;
}
