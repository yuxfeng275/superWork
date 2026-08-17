package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailIntegrationConfigRequest {
    private Boolean deepSeekEnabled = false;
    @NotBlank(message = "DeepSeek 服务地址不能为空")
    private String deepSeekBaseUrl;
    @NotBlank(message = "DeepSeek 模型不能为空")
    private String deepSeekModel;
    private String deepSeekApiKey;
    private Boolean weComEnabled = false;
    @NotBlank(message = "企业微信服务地址不能为空")
    private String weComBaseUrl;
    private String weComCorpId;
    private String weComAgentId;
    private String weComSecret;
    private String publicBaseUrl;
}
