package com.bu.management.config;

import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

public record WorktimeRuntimeConfig(
        boolean enabled,
        String baseUrl,
        String employeeNo,
        String password,
        String credentialSource,
        LocalDateTime lastTestedAt,
        String lastTestStatus,
        String lastTestMessage
) {
    public boolean hasCredentials() {
        return StringUtils.hasText(baseUrl)
                && StringUtils.hasText(employeeNo) && StringUtils.hasText(password);
    }

    public boolean isConfigured() {
        return enabled && hasCredentials();
    }

    public String effectiveBaseUrl() {
        if (baseUrl == null) return null;
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
