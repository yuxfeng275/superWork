package com.bu.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@Component
@ConfigurationProperties(prefix = "yunxiao")
public class YunxiaoProperties {
    private boolean enabled;
    private String edition = "center";
    private String baseUrl = "https://openapi-rdc.aliyuncs.com";
    private String organizationId;
    private String token;
    private String configEncryptionKey;
    private String syncCron = "0 15 * * * *";
    private String handoffRetryCron = "0 */10 * * * *";

    public boolean isConfigured() {
        boolean basic = enabled && StringUtils.hasText(baseUrl) && StringUtils.hasText(token);
        return isRegionEdition() ? basic : basic && StringUtils.hasText(organizationId);
    }

    public boolean isRegionEdition() {
        return "region".equalsIgnoreCase(edition);
    }
}
