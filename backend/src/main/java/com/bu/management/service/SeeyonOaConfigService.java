package com.bu.management.service;

import com.bu.management.config.SeeyonOaProperties;
import com.bu.management.config.SeeyonOaRuntimeConfig;
import com.bu.management.config.SeeyonOaTokenCipher;
import com.bu.management.dto.SeeyonOaConfigRequest;
import com.bu.management.entity.SeeyonOaIntegrationConfig;
import com.bu.management.mapper.SeeyonOaIntegrationConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SeeyonOaConfigService {

    private static final Long CONFIG_ID = 1L;

    private final SeeyonOaIntegrationConfigMapper configMapper;
    private final SeeyonOaProperties environment;
    private final SeeyonOaTokenCipher tokenCipher;

    public SeeyonOaRuntimeConfig getRuntimeConfig() {
        SeeyonOaIntegrationConfig stored = configMapper.selectById(CONFIG_ID);
        if (stored == null) {
            return new SeeyonOaRuntimeConfig(
                    environment.isEnabled(),
                    environment.getBaseUrl(),
                    environment.getUsername(),
                    environment.getPassword(),
                    environment.getToken(),
                    StringUtils.hasText(environment.getToken()) ? "ENVIRONMENT" : "NONE",
                    null, null, null
            );
        }

        String username = StringUtils.hasText(stored.getEncryptedUsername())
                ? tokenCipher.decrypt(stored.getEncryptedUsername())
                : environment.getUsername();
        String password = StringUtils.hasText(stored.getEncryptedPassword())
                ? tokenCipher.decrypt(stored.getEncryptedPassword())
                : environment.getPassword();
        String token = StringUtils.hasText(stored.getEncryptedToken())
                ? tokenCipher.decrypt(stored.getEncryptedToken())
                : environment.getToken();
        String tokenSource = StringUtils.hasText(stored.getEncryptedToken())
                ? "PAGE" : StringUtils.hasText(environment.getToken()) ? "ENVIRONMENT" : "NONE";

        return new SeeyonOaRuntimeConfig(
                Integer.valueOf(1).equals(stored.getEnabled()),
                stored.getBaseUrl(),
                username,
                password,
                token,
                tokenSource,
                stored.getLastTestedAt(),
                stored.getLastTestStatus(),
                stored.getLastTestMessage()
        );
    }

    @Transactional
    public SeeyonOaRuntimeConfig save(SeeyonOaConfigRequest request, Long userId) {
        String baseUrl = normalizeBaseUrl(request.getBaseUrl());
        boolean enabled = Boolean.TRUE.equals(request.getEnabled());

        if (enabled && !StringUtils.hasText(request.getUsername()) && !StringUtils.hasText(request.getToken())) {
            throw new RuntimeException("启用 OA 集成前必须配置用户名密码或访问令牌");
        }

        SeeyonOaIntegrationConfig stored = configMapper.selectById(CONFIG_ID);
        boolean newConfig = stored == null;
        LocalDateTime now = LocalDateTime.now();

        String encryptedUsername = null;
        String encryptedPassword = null;
        String encryptedToken = null;

        if (StringUtils.hasText(request.getUsername())) {
            encryptedUsername = tokenCipher.encrypt(request.getUsername().trim());
        }
        if (StringUtils.hasText(request.getPassword())) {
            encryptedPassword = tokenCipher.encrypt(request.getPassword().trim());
        }
        if (StringUtils.hasText(request.getToken())) {
            encryptedToken = tokenCipher.encrypt(request.getToken().trim());
        }

        if (newConfig) {
            stored = new SeeyonOaIntegrationConfig();
            stored.setId(CONFIG_ID);
            stored.setCreatedAt(now);
        }
        stored.setEnabled(enabled ? 1 : 0);
        stored.setBaseUrl(baseUrl);
        stored.setEncryptedUsername(encryptedUsername);
        stored.setEncryptedPassword(encryptedPassword);
        stored.setEncryptedToken(encryptedToken);
        stored.setUpdatedBy(userId);
        stored.setLastTestedAt(null);
        stored.setLastTestStatus(null);
        stored.setLastTestMessage(null);
        stored.setUpdatedAt(now);

        if (newConfig) {
            configMapper.insert(stored);
        } else {
            configMapper.updateById(stored);
        }
        return getRuntimeConfig();
    }

    @Transactional
    public void recordConnectionTest(boolean success, String message, LocalDateTime testedAt) {
        SeeyonOaIntegrationConfig stored = configMapper.selectById(CONFIG_ID);
        if (stored == null) return;
        stored.setLastTestedAt(testedAt);
        stored.setLastTestStatus(success ? "SUCCESS" : "FAILED");
        stored.setLastTestMessage(limit(message, 500));
        stored.setUpdatedAt(LocalDateTime.now());
        configMapper.updateById(stored);
    }

    private String normalizeBaseUrl(String value) {
        if (!StringUtils.hasText(value)) {
            throw new RuntimeException("OA 服务地址不能为空");
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase();
            if (scheme == null || !Set.of("http", "https").contains(scheme)
                    || !StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (StringUtils.hasText(uri.getPath()) && !"/".equals(uri.getPath()))) {
                throw new RuntimeException("OA 服务地址必须是有效的 HTTP(S) 根地址");
            }
            String normalized = uri.toString();
            return normalized.endsWith("/")
                    ? normalized.substring(0, normalized.length() - 1)
                    : normalized;
        } catch (URISyntaxException ex) {
            throw new RuntimeException("OA 服务地址格式不正确");
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}