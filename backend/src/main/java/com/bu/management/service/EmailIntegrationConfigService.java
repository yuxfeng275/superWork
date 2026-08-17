package com.bu.management.service;

import com.bu.management.config.EmailIntegrationRuntimeConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailIntegrationConfigService {
    public static final String GROUP = "email-integration";

    private final SystemConfigService systemConfigService;

    public EmailIntegrationRuntimeConfig getRuntimeConfig() {
        return new EmailIntegrationRuntimeConfig(
                systemConfigService.getBoolean(GROUP, "deepseek.enabled", false),
                systemConfigService.getValue(GROUP, "deepseek.base-url", "https://api.deepseek.com"),
                systemConfigService.getValue(GROUP, "deepseek.model", "deepseek-chat"),
                systemConfigService.getValue(GROUP, "deepseek.api-key", null),
                systemConfigService.getBoolean(GROUP, "wecom.enabled", false),
                systemConfigService.getValue(GROUP, "wecom.base-url", "https://qyapi.weixin.qq.com"),
                systemConfigService.getValue(GROUP, "wecom.corp-id", null),
                systemConfigService.getValue(GROUP, "wecom.agent-id", null),
                systemConfigService.getValue(GROUP, "wecom.secret", null),
                systemConfigService.getValue(GROUP, "app.public-base-url", null));
    }
}
