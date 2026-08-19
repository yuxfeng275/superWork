package com.bu.management.service;

import com.bu.management.config.YunxiaoProperties;
import com.bu.management.config.YunxiaoRuntimeConfig;
import com.bu.management.config.YunxiaoTokenCipher;
import com.bu.management.dto.YunxiaoConfigRequest;
import com.bu.management.entity.YunxiaoIntegrationConfig;
import com.bu.management.mapper.YunxiaoIntegrationConfigMapper;
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
public class YunxiaoConfigService {

    private static final Long CONFIG_ID = 1L;
    private static final Set<String> EDITIONS = Set.of("center", "region");

    private final YunxiaoIntegrationConfigMapper configMapper;
    private final YunxiaoProperties environment;
    private final YunxiaoTokenCipher tokenCipher;

    public YunxiaoRuntimeConfig getRuntimeConfig() {
        YunxiaoIntegrationConfig stored = configMapper.selectById(CONFIG_ID);
        if (stored == null) {
            return new YunxiaoRuntimeConfig(
                    environment.isEnabled(),
                    normalizeEdition(environment.getEdition()),
                    environment.getBaseUrl(),
                    environment.getOrganizationId(),
                    environment.getToken(),
                    StringUtils.hasText(environment.getToken()) ? "ENVIRONMENT" : "NONE",
                    null,
                    null,
                    null
            );
        }

        String token = environment.getToken();
        String tokenSource = StringUtils.hasText(token) ? "ENVIRONMENT" : "NONE";
        String lastTestStatus = stored.getLastTestStatus();
        String lastTestMessage = stored.getLastTestMessage();
        if (StringUtils.hasText(stored.getEncryptedToken())) {
            try {
                token = tokenCipher.decrypt(stored.getEncryptedToken());
                tokenSource = "PAGE";
            } catch (IllegalStateException exception) {
                token = null;
                tokenSource = "UNREADABLE";
                lastTestStatus = "CONFIG_ERROR";
                lastTestMessage = "云效令牌无法解密，请在云效配置中重新录入个人访问令牌";
            }
        }
        return new YunxiaoRuntimeConfig(
                Integer.valueOf(1).equals(stored.getEnabled()),
                normalizeEdition(stored.getEdition()),
                stored.getBaseUrl(),
                stored.getOrganizationId(),
                token,
                tokenSource,
                stored.getLastTestedAt(),
                lastTestStatus,
                lastTestMessage
        );
    }

    @Transactional
    public YunxiaoRuntimeConfig save(YunxiaoConfigRequest request, Long userId) {
        String edition = normalizeEdition(request.getEdition());
        if (!EDITIONS.contains(edition)) {
            throw new RuntimeException("云效版本仅支持中心化版本或专有云版本");
        }
        String baseUrl = normalizeBaseUrl(request.getBaseUrl());
        String organizationId = trimToNull(request.getOrganizationId());
        boolean enabled = Boolean.TRUE.equals(request.getEnabled());

        YunxiaoIntegrationConfig stored = configMapper.selectById(CONFIG_ID);
        boolean newConfig = stored == null;
        String encryptedToken = stored == null ? null : stored.getEncryptedToken();
        if (StringUtils.hasText(request.getToken())) {
            encryptedToken = tokenCipher.encrypt(request.getToken().trim());
        }

        boolean tokenAvailable = StringUtils.hasText(environment.getToken());
        if (StringUtils.hasText(encryptedToken)) {
            try {
                tokenCipher.decrypt(encryptedToken);
                tokenAvailable = true;
            } catch (IllegalStateException exception) {
                if (!StringUtils.hasText(request.getToken())) {
                    throw new RuntimeException("现有云效令牌无法解密，请重新录入个人访问令牌");
                }
            }
        }
        if (enabled && !tokenAvailable) {
            throw new RuntimeException("启用云效集成前必须配置个人访问令牌");
        }
        if (enabled && "center".equals(edition) && !StringUtils.hasText(organizationId)) {
            throw new RuntimeException("中心化版本启用前必须配置组织ID");
        }

        LocalDateTime now = LocalDateTime.now();
        if (newConfig) {
            stored = new YunxiaoIntegrationConfig();
            stored.setId(CONFIG_ID);
            stored.setCreatedAt(now);
        }
        stored.setEnabled(enabled ? 1 : 0);
        stored.setEdition(edition);
        stored.setBaseUrl(baseUrl);
        stored.setOrganizationId(organizationId);
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
        YunxiaoIntegrationConfig stored = configMapper.selectById(CONFIG_ID);
        if (stored == null) {
            return;
        }
        stored.setLastTestedAt(testedAt);
        stored.setLastTestStatus(success ? "SUCCESS" : "FAILED");
        stored.setLastTestMessage(limit(message, 500));
        stored.setUpdatedAt(LocalDateTime.now());
        configMapper.updateById(stored);
    }

    private String normalizeEdition(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "center";
    }

    private String normalizeBaseUrl(String value) {
        if (!StringUtils.hasText(value)) {
            throw new RuntimeException("云效服务地址不能为空");
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null
                    ? null
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            if (scheme == null
                    || !Set.of("http", "https").contains(scheme)
                    || !StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (StringUtils.hasText(uri.getPath()) && !"/".equals(uri.getPath()))) {
                throw new RuntimeException("云效服务地址必须是有效的 HTTP(S) 根地址");
            }
            String normalized = uri.toString();
            return normalized.endsWith("/")
                    ? normalized.substring(0, normalized.length() - 1)
                    : normalized;
        } catch (URISyntaxException ex) {
            throw new RuntimeException("云效服务地址格式不正确");
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
