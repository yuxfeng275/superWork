package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SeeyonOaConfigRequest {
    private Boolean enabled;
    @NotBlank(message = "OA 服务地址不能为空")
    private String baseUrl;
    private String username;
    private String password;
    private String token;
}