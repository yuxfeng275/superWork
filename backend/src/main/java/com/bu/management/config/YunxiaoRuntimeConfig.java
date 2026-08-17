package com.bu.management.config;

import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

public record YunxiaoRuntimeConfig(
        boolean enabled,
        String edition,
        String baseUrl,
        String organizationId,
        String token,
        String tokenSource,
        LocalDateTime lastTestedAt,
        String lastTestStatus,
        String lastTestMessage
) {
    public boolean isRegionEdition() {
        return "region".equalsIgnoreCase(edition);
    }

    public boolean hasConnectionParameters() {
        return isRegionEdition()
                ? hasCredentials()
                : hasCredentials() && StringUtils.hasText(organizationId);
    }

    public boolean hasCredentials() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(token);
    }

    public boolean isConfigured() {
        return enabled && hasConnectionParameters();
    }
}
