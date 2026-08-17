package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class YunxiaoConfigRequest {
    private Boolean enabled;

    @NotBlank(message = "云效版本不能为空")
    @Size(max = 20, message = "云效版本长度不能超过20个字符")
    private String edition;

    @NotBlank(message = "云效服务地址不能为空")
    @Size(max = 500, message = "云效服务地址长度不能超过500个字符")
    private String baseUrl;

    @Size(max = 100, message = "云效组织ID长度不能超过100个字符")
    private String organizationId;

    @Size(max = 2000, message = "云效令牌长度不能超过2000个字符")
    private String token;
}
