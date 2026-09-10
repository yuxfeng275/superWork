package com.bu.management.service;

import com.bu.management.config.WorktimeProperties;
import com.bu.management.config.WorktimeRuntimeConfig;
import com.bu.management.config.WorktimeTokenCipher;
import com.bu.management.dto.WorktimeConfigRequest;
import com.bu.management.entity.WorktimeIntegrationConfig;
import com.bu.management.mapper.WorktimeIntegrationConfigMapper;
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
public class WorktimeConfigService {

    private static final Long CONFIG_ID = 1L;

    private final WorktimeIntegrationConfigMapper configMapper;
    private final WorktimeProperties environment;
    private final WorktimeTokenCipher tokenCipher;

    public WorktimeRuntimeConfig getRuntimeConfig() {
        WorktimeIntegrationConfig stored = configMapper.selectById(CONFIG_ID);
        if (stored == null) {
            return new WorktimeRuntimeConfig(
                    environment.isEnabled(),
                    environment.getBaseUrl(),
                    environment.getEmployeeNo(),
                    environment.getPassword(),
                    StringUtils.hasText(environment.getEmployeeNo()) ? "ENVIRONMENT" : "NONE",
                    null, null, null);
        }
        String employeeNo = StringUtils.hasText(stored.getEncryptedEmployeeNo())
                ? tokenCipher.decrypt(stored.getEncryptedEmployeeNo()) : environment.getEmployeeNo();
        String password = StringUtils.hasText(stored.getEncryptedPassword())
                ? tokenCipher.decrypt(stored.getEncryptedPassword()) : environment.getPassword();
        String source = StringUtils.hasText(stored.getEncryptedEmployeeNo()) ? "PAGE"
                : StringUtils.hasText(environment.getEmployeeNo()) ? "ENVIRONMENT" : "NONE";
        return new WorktimeRuntimeConfig(
                Integer.valueOf(1).equals(stored.getEnabled()),
                stored.getBaseUrl(),
                employeeNo,
                password,
                source,
                stored.getLastTestedAt(),
                stored.getLastTestStatus(),
                stored.getLastTestMessage());
    }

    @Transactional
    public WorktimeRuntimeConfig save(WorktimeConfigRequest request, Long userId) {
        String baseUrl = normalizeBaseUrl(request.getBaseUrl());
        boolean enabled = Boolean.TRUE.equals(request.getEnabled());

        WorktimeIntegrationConfig stored = configMapper.selectById(CONFIG_ID);
        boolean newConfig = stored == null;
        LocalDateTime now = LocalDateTime.now();

        // 未传凭据时保留已有配置
        String encryptedEmployeeNo = StringUtils.hasText(request.getEmployeeNo())
                ? tokenCipher.encrypt(request.getEmployeeNo().trim())
                : (newConfig ? null : stored.getEncryptedEmployeeNo());
        String encryptedPassword = StringUtils.hasText(request.getPassword())
                ? tokenCipher.encrypt(request.getPassword().trim())
                : (newConfig ? null : stored.getEncryptedPassword());

        if (enabled && !StringUtils.hasText(encryptedEmployeeNo) && !StringUtils.hasText(environment.getEmployeeNo())) {
            throw new RuntimeException("启用工时系统集成前必须配置登录工号和密码");
        }

        if (newConfig) {
            stored = new WorktimeIntegrationConfig();
            stored.setId(CONFIG_ID);
            stored.setCreatedAt(now);
        }
        stored.setEnabled(enabled ? 1 : 0);
        stored.setBaseUrl(baseUrl);
        stored.setEncryptedEmployeeNo(encryptedEmployeeNo);
        stored.setEncryptedPassword(encryptedPassword);
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
        WorktimeIntegrationConfig stored = configMapper.selectById(CONFIG_ID);
        if (stored == null) return;
        stored.setLastTestedAt(testedAt);
        stored.setLastTestStatus(success ? "SUCCESS" : "FAILED");
        stored.setLastTestMessage(message == null || message.length() <= 500 ? message : message.substring(0, 500));
        stored.setUpdatedAt(LocalDateTime.now());
        configMapper.updateById(stored);
    }

    private String normalizeBaseUrl(String value) {
        if (!StringUtils.hasText(value)) {
            throw new RuntimeException("工时系统地址不能为空");
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
                throw new RuntimeException("工时系统地址必须是有效的 HTTP(S) 根地址");
            }
            String normalized = uri.toString();
            return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        } catch (URISyntaxException ex) {
            throw new RuntimeException("工时系统地址格式不正确");
        }
    }
}
