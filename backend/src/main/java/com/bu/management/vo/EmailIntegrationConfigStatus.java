package com.bu.management.vo;

import java.time.LocalDateTime;

public record EmailIntegrationConfigStatus(
        boolean configured,
        boolean deepSeekEnabled,
        String deepSeekBaseUrl,
        String deepSeekModel,
        boolean deepSeekApiKeyConfigured,
        String deepSeekTestStatus,
        String deepSeekTestMessage,
        LocalDateTime deepSeekTestedAt,
        boolean weComEnabled,
        String weComBaseUrl,
        String weComCorpId,
        String weComAgentId,
        boolean weComSecretConfigured,
        String publicBaseUrl,
        String weComTestStatus,
        String weComTestMessage,
        LocalDateTime weComTestedAt) {
}
