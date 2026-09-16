package com.bu.management.service;

import com.bu.management.config.SeeyonOaProperties;
import com.bu.management.config.SeeyonOaRuntimeConfig;
import com.bu.management.entity.Connector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 致远 OA 运行时配置：连接参数唯一来源是连接器注册表（code=oa），
 * 环境变量作为兜底（未在页面上配置时生效）。页面配置入口统一在「连接器管理」。
 *
 * @author BU Team
 */
@Service
@RequiredArgsConstructor
public class SeeyonOaConfigService {

    private final ConnectorRegistryService registryService;
    private final SeeyonOaProperties environment;

    public SeeyonOaRuntimeConfig getRuntimeConfig() {
        Connector connector = registryService.findByCode(ConnectorRegistryService.CODE_OA).orElse(null);
        if (connector == null) {
            String token = environment.getToken();
            return new SeeyonOaRuntimeConfig(
                    environment.isEnabled(),
                    environment.getBaseUrl(),
                    environment.getUsername(),
                    environment.getPassword(),
                    token,
                    StringUtils.hasText(token) ? "ENVIRONMENT" : "NONE",
                    null,
                    null,
                    null
            );
        }
        String username = environment.getUsername();
        String password = environment.getPassword();
        String token = environment.getToken();
        String tokenSource = StringUtils.hasText(token) ? "ENVIRONMENT" : "NONE";
        if (StringUtils.hasText(connector.getEncryptedUsername())) {
            username = registryService.credential(connector, "username");
        }
        if (StringUtils.hasText(connector.getEncryptedPassword())) {
            password = registryService.credential(connector, "password");
        }
        if (StringUtils.hasText(connector.getEncryptedToken())) {
            token = registryService.credential(connector, "token");
            tokenSource = "PAGE";
        }
        return new SeeyonOaRuntimeConfig(
                Integer.valueOf(1).equals(connector.getEnabled()),
                StringUtils.hasText(connector.getBaseUrl()) ? connector.getBaseUrl() : environment.getBaseUrl(),
                username,
                password,
                token,
                tokenSource,
                connector.getLastTestedAt(),
                connector.getLastTestStatus(),
                connector.getLastTestMessage()
        );
    }
}
