package com.bu.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@Component
@ConfigurationProperties(prefix = "seeyon-oa")
public class SeeyonOaProperties {
    private boolean enabled;
    private String baseUrl = "https://oa.lucidata.cn";
    private String username;
    private String password;
    private String token;
    private String configEncryptionKey;
    private String syncCron = "0 30 * * * *";

    public boolean isConfigured() {
        return enabled && StringUtils.hasText(baseUrl)
                && (StringUtils.hasText(token) || (StringUtils.hasText(username) && StringUtils.hasText(password)));
    }
}