package com.bu.management.service;

import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.entity.Connector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 邮件摘要与推送的运行时配置：DeepSeek / 企业微信 / 系统外链地址均取连接器注册表
 * （code=deepseek / wecom / mail），连接参数唯一来源见「连接器管理」。
 *
 * @author BU Team
 */
@Service
@RequiredArgsConstructor
public class EmailIntegrationConfigService {

    private final ConnectorRegistryService registryService;

    public EmailIntegrationRuntimeConfig getRuntimeConfig() {
        Connector deepSeek = registryService.findByCode(ConnectorRegistryService.CODE_DEEPSEEK).orElse(null);
        Connector weCom = registryService.findByCode(ConnectorRegistryService.CODE_WECOM).orElse(null);
        Connector mail = registryService.findByCode(ConnectorRegistryService.CODE_MAIL).orElse(null);
        return new EmailIntegrationRuntimeConfig(
                deepSeekEnabled(deepSeek),
                deepSeek == null ? null : deepSeek.getBaseUrl(),
                deepSeekModel(deepSeek),
                deepSeek == null ? null : registryService.credential(deepSeek, "token"),
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

    /** 每日摘要是否使用 DeepSeek：连接器启用且显式勾选摘要用途。 */
    private boolean deepSeekEnabled(Connector connector) {
        return enabled(connector) && Boolean.parseBoolean(registryService.extra(connector, "digestEnabled", "false"));
    }

    /** 摘要模型：优先「邮件摘要模型」，缺省回落到助手模型。 */
    private String deepSeekModel(Connector connector) {
        if (connector == null) return null;
        String digestModel = registryService.extra(connector, "digestModel");
        return StringUtils.hasText(digestModel) ? digestModel : registryService.extra(connector, "model");
    }

    private boolean enabled(Connector connector) {
        return connector != null && Integer.valueOf(1).equals(connector.getEnabled());
    }
}
