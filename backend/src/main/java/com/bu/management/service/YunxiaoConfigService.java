package com.bu.management.service;

import com.bu.management.config.YunxiaoProperties;
import com.bu.management.config.YunxiaoRuntimeConfig;
import com.bu.management.entity.Connector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * 云效运行时配置：连接参数唯一来源是连接器注册表（code=yunxiao），
 * 环境变量作为兜底（未在页面上配置时生效）。页面配置入口统一在「连接器管理」。
 *
 * @author BU Team
 */
@Service
@RequiredArgsConstructor
public class YunxiaoConfigService {

    private static final Set<String> EDITIONS = Set.of("center", "region");

    private final ConnectorRegistryService registryService;
    private final YunxiaoProperties environment;

    public YunxiaoRuntimeConfig getRuntimeConfig() {
        Connector connector = registryService.findByCode(ConnectorRegistryService.CODE_YUNXIAO).orElse(null);
        if (connector == null) {
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
        String lastTestStatus = connector.getLastTestStatus();
        String lastTestMessage = connector.getLastTestMessage();
        if (StringUtils.hasText(connector.getEncryptedToken())) {
            try {
                token = registryService.credential(connector, "token");
                tokenSource = "PAGE";
            } catch (IllegalStateException exception) {
                token = null;
                tokenSource = "UNREADABLE";
                lastTestStatus = "CONFIG_ERROR";
                lastTestMessage = "云效令牌无法解密，请在「连接器管理」重新录入个人访问令牌";
            }
        }
        return new YunxiaoRuntimeConfig(
                Integer.valueOf(1).equals(connector.getEnabled()),
                normalizeEdition(registryService.extra(connector, "edition", environment.getEdition())),
                StringUtils.hasText(connector.getBaseUrl()) ? connector.getBaseUrl() : environment.getBaseUrl(),
                registryService.extra(connector, "organizationId", environment.getOrganizationId()),
                token,
                tokenSource,
                connector.getLastTestedAt(),
                lastTestStatus,
                lastTestMessage
        );
    }

    private String normalizeEdition(String value) {
        String edition = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "center";
        return EDITIONS.contains(edition) ? edition : "center";
    }
}
