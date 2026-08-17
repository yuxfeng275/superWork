package com.bu.management.service;

import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.dto.EmailIntegrationConfigRequest;
import com.bu.management.entity.EmailIntegrationConfig;
import com.bu.management.mapper.EmailIntegrationConfigMapper;
import com.bu.management.vo.EmailIntegrationConfigStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EmailIntegrationConfigService {
    private static final Long CONFIG_ID = 1L;
    private static final String DEFAULT_DEEPSEEK_URL = "https://api.deepseek.com";
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-chat";
    private static final String DEFAULT_WECOM_URL = "https://qyapi.weixin.qq.com";

    private final EmailIntegrationConfigMapper mapper;
    private final EmailCredentialCipher cipher;

    public EmailIntegrationConfigStatus getStatus() {
        EmailIntegrationConfig config = mapper.selectById(CONFIG_ID);
        if (config == null) {
            return emptyStatus();
        }
        return toStatus(config);
    }

    public EmailIntegrationRuntimeConfig getRuntimeConfig() {
        EmailIntegrationConfig config = mapper.selectById(CONFIG_ID);
        if (config == null) {
            return new EmailIntegrationRuntimeConfig(false, DEFAULT_DEEPSEEK_URL,
                    DEFAULT_DEEPSEEK_MODEL, null, false, DEFAULT_WECOM_URL,
                    null, null, null, null);
        }
        return new EmailIntegrationRuntimeConfig(
                enabled(config.getDeepSeekEnabled()),
                defaultText(config.getDeepSeekBaseUrl(), DEFAULT_DEEPSEEK_URL),
                defaultText(config.getDeepSeekModel(), DEFAULT_DEEPSEEK_MODEL),
                decrypt(config.getEncryptedDeepSeekApiKey()),
                enabled(config.getWeComEnabled()),
                defaultText(config.getWeComBaseUrl(), DEFAULT_WECOM_URL),
                trimToNull(config.getWeComCorpId()),
                trimToNull(config.getWeComAgentId()),
                decrypt(config.getEncryptedWeComSecret()),
                trimToNull(config.getPublicBaseUrl()));
    }

    @Transactional
    public EmailIntegrationConfigStatus save(EmailIntegrationConfigRequest request, Long userId) {
        EmailIntegrationConfig config = mapper.selectById(CONFIG_ID);
        boolean insert = config == null;
        LocalDateTime now = LocalDateTime.now();
        if (insert) {
            config = new EmailIntegrationConfig();
            config.setId(CONFIG_ID);
            config.setCreatedAt(now);
        }

        String deepSeekBaseUrl = normalizeRootUrl(request.getDeepSeekBaseUrl(), "DeepSeek");
        String deepSeekModel = request.getDeepSeekModel().trim();
        String weComBaseUrl = normalizeRootUrl(request.getWeComBaseUrl(), "企业微信");
        String corpId = trimToNull(request.getWeComCorpId());
        String agentId = trimToNull(request.getWeComAgentId());
        String publicBaseUrl = normalizeOptionalRootUrl(request.getPublicBaseUrl(), "系统访问地址");

        String encryptedApiKey = config.getEncryptedDeepSeekApiKey();
        if (StringUtils.hasText(request.getDeepSeekApiKey())) {
            encryptedApiKey = cipher.encrypt(request.getDeepSeekApiKey().trim());
        }
        String encryptedWeComSecret = config.getEncryptedWeComSecret();
        if (StringUtils.hasText(request.getWeComSecret())) {
            encryptedWeComSecret = cipher.encrypt(request.getWeComSecret().trim());
        }
        boolean deepSeekEnabled = Boolean.TRUE.equals(request.getDeepSeekEnabled());
        boolean weComEnabled = Boolean.TRUE.equals(request.getWeComEnabled());
        if (deepSeekEnabled && !StringUtils.hasText(encryptedApiKey)) {
            throw new IllegalStateException("启用 DeepSeek 前必须配置 API Key");
        }
        if (weComEnabled && (!StringUtils.hasText(corpId) || !StringUtils.hasText(agentId)
                || !StringUtils.hasText(encryptedWeComSecret))) {
            throw new IllegalStateException("启用企业微信前必须配置 CorpId、AgentId 和 Secret");
        }

        config.setDeepSeekEnabled(deepSeekEnabled ? 1 : 0);
        config.setDeepSeekBaseUrl(deepSeekBaseUrl);
        config.setDeepSeekModel(deepSeekModel);
        config.setEncryptedDeepSeekApiKey(encryptedApiKey);
        config.setDeepSeekTestStatus(null);
        config.setDeepSeekTestMessage(null);
        config.setDeepSeekTestedAt(null);
        config.setWeComEnabled(weComEnabled ? 1 : 0);
        config.setWeComBaseUrl(weComBaseUrl);
        config.setWeComCorpId(corpId);
        config.setWeComAgentId(agentId);
        config.setEncryptedWeComSecret(encryptedWeComSecret);
        config.setPublicBaseUrl(publicBaseUrl);
        config.setWeComTestStatus(null);
        config.setWeComTestMessage(null);
        config.setWeComTestedAt(null);
        config.setUpdatedBy(userId);
        config.setUpdatedAt(now);
        if (insert) {
            mapper.insert(config);
        } else {
            mapper.updateById(config);
        }
        return toStatus(config);
    }

    @Transactional
    public void recordDeepSeekTest(boolean success, String message, LocalDateTime testedAt) {
        EmailIntegrationConfig config = mapper.selectById(CONFIG_ID);
        if (config == null) return;
        config.setDeepSeekTestStatus(success ? "SUCCESS" : "FAILED");
        config.setDeepSeekTestMessage(limit(message));
        config.setDeepSeekTestedAt(testedAt);
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
    }

    @Transactional
    public void recordWeComTest(boolean success, String message, LocalDateTime testedAt) {
        EmailIntegrationConfig config = mapper.selectById(CONFIG_ID);
        if (config == null) return;
        config.setWeComTestStatus(success ? "SUCCESS" : "FAILED");
        config.setWeComTestMessage(limit(message));
        config.setWeComTestedAt(testedAt);
        config.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(config);
    }

    private EmailIntegrationConfigStatus emptyStatus() {
        return new EmailIntegrationConfigStatus(false, false, DEFAULT_DEEPSEEK_URL,
                DEFAULT_DEEPSEEK_MODEL, false, null, null, null,
                false, DEFAULT_WECOM_URL, null, null, false, null,
                null, null, null);
    }

    private EmailIntegrationConfigStatus toStatus(EmailIntegrationConfig config) {
        return new EmailIntegrationConfigStatus(true, enabled(config.getDeepSeekEnabled()),
                defaultText(config.getDeepSeekBaseUrl(), DEFAULT_DEEPSEEK_URL),
                defaultText(config.getDeepSeekModel(), DEFAULT_DEEPSEEK_MODEL),
                StringUtils.hasText(config.getEncryptedDeepSeekApiKey()),
                config.getDeepSeekTestStatus(), config.getDeepSeekTestMessage(),
                config.getDeepSeekTestedAt(), enabled(config.getWeComEnabled()),
                defaultText(config.getWeComBaseUrl(), DEFAULT_WECOM_URL),
                config.getWeComCorpId(), config.getWeComAgentId(),
                StringUtils.hasText(config.getEncryptedWeComSecret()), config.getPublicBaseUrl(),
                config.getWeComTestStatus(), config.getWeComTestMessage(),
                config.getWeComTestedAt());
    }

    private String decrypt(String encrypted) {
        return StringUtils.hasText(encrypted) ? cipher.decrypt(encrypted) : null;
    }

    private boolean enabled(Integer value) {
        return Integer.valueOf(1).equals(value);
    }

    private String normalizeRootUrl(String value, String label) {
        if (!StringUtils.hasText(value)) throw new IllegalStateException(label + "服务地址不能为空");
        return validateRootUrl(value.trim(), label);
    }

    private String normalizeOptionalRootUrl(String value, String label) {
        return StringUtils.hasText(value) ? validateRootUrl(value.trim(), label) : null;
    }

    private String validateRootUrl(String value, String label) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || !StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (StringUtils.hasText(uri.getPath()) && !"/".equals(uri.getPath()))) {
                throw new IllegalStateException(label + "必须是有效的 HTTP(S) 根地址");
            }
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IllegalStateException(label + "地址格式不正确");
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String limit(String value) {
        if (value == null || value.length() <= 500) return value;
        return value.substring(0, 500);
    }
}
