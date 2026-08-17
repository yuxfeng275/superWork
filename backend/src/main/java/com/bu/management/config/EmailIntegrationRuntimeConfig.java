package com.bu.management.config;

import org.springframework.util.StringUtils;

public record EmailIntegrationRuntimeConfig(
        boolean deepSeekEnabled,
        String deepSeekBaseUrl,
        String deepSeekModel,
        String deepSeekApiKey,
        boolean weComEnabled,
        String weComBaseUrl,
        String weComCorpId,
        String weComAgentId,
        String weComSecret,
        String publicBaseUrl) {

    public boolean isDeepSeekConfigured() {
        return deepSeekEnabled && StringUtils.hasText(deepSeekBaseUrl)
                && StringUtils.hasText(deepSeekModel) && StringUtils.hasText(deepSeekApiKey);
    }

    public boolean isWeComConfigured() {
        return weComEnabled && StringUtils.hasText(weComBaseUrl)
                && StringUtils.hasText(weComCorpId) && StringUtils.hasText(weComAgentId)
                && StringUtils.hasText(weComSecret);
    }
}
