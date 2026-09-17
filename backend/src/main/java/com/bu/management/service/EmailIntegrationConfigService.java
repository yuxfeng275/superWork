package com.bu.management.service;

import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.entity.Connector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 邮件摘要与推送的运行时配置：
 * 摘要模型取自「模型管理」（勾选「用于邮件摘要」且提供方连接器就绪的模型），
 * 企业微信与系统外链地址取自连接器注册表（code=wecom / mail）。
 *
 * @author BU Team
 */
@Service
@RequiredArgsConstructor
public class EmailIntegrationConfigService {

    private final ConnectorRegistryService registryService;
    private final AiModelConfigService aiModelConfigService;

    public EmailIntegrationRuntimeConfig getRuntimeConfig() {
        AiModelConfigService.DigestModel digest = aiModelConfigService.digestModel().orElse(null);
        Connector weCom = registryService.findByCode(ConnectorRegistryService.CODE_WECOM).orElse(null);
        Connector mail = registryService.findByCode(ConnectorRegistryService.CODE_MAIL).orElse(null);
        return new EmailIntegrationRuntimeConfig(
                digest != null,
                digest == null ? null : digest.baseUrl(),
                digest == null ? null : digest.model(),
                digest == null ? null : digest.apiKey(),
                enabled(weCom),
                weCom == null ? null : weCom.getBaseUrl(),
                weCom == null ? null : registryService.extra(weCom, "corpId"),
                weCom == null ? null : registryService.extra(weCom, "agentId"),
                weCom == null ? null : registryService.credential(weCom, "token"),
                mail == null ? null : mail.getBaseUrl());
    }

    public String publicBaseUrl() {
        return registryService.findByCode(ConnectorRegistryService.CODE_MAIL)
                .map(Connector::getBaseUrl)
                .filter(StringUtils::hasText)
                .orElse(null);
    }

    private boolean enabled(Connector connector) {
        return connector != null && Integer.valueOf(1).equals(connector.getEnabled());
    }
}
