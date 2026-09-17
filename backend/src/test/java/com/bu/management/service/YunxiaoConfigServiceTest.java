package com.bu.management.service;

import com.bu.management.config.YunxiaoProperties;
import com.bu.management.config.YunxiaoRuntimeConfig;
import com.bu.management.entity.Connector;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 云效运行时配置：连接参数从连接器注册表读取（env 兜底），
 * 不再有独立的配置表读写路径。
 */
@ExtendWith(MockitoExtension.class)
class YunxiaoConfigServiceTest {

    @Mock
    private ConnectorRegistryService registryService;

    private YunxiaoProperties environment;
    private YunxiaoConfigService service;

    @BeforeEach
    void setUp() {
        environment = new YunxiaoProperties();
        service = new YunxiaoConfigService(registryService, environment);
    }

    private Connector connector(Integer enabled, String baseUrl, String encryptedToken, String extraConfig) {
        Connector connector = new Connector();
        connector.setId(9L);
        connector.setCode(ConnectorRegistryService.CODE_YUNXIAO);
        connector.setEnabled(enabled);
        connector.setBaseUrl(baseUrl);
        connector.setEncryptedToken(encryptedToken);
        connector.setExtraConfig(extraConfig);
        return connector;
    }

    private void stubConnector(Connector connector) {
        when(registryService.findByCode(ConnectorRegistryService.CODE_YUNXIAO)).thenReturn(Optional.of(connector));
    }

    private void stubExtra(Connector connector, String key, String value, String fallback) {
        lenient().when(registryService.extra(eq(connector), eq(key), any())).thenReturn(value == null ? fallback : value);
    }

    @Test
    @DisplayName("连接器行存在时：地址/组织 ID/令牌全部取自连接器，令牌来源标记 PAGE")
    void readsConnectorConfiguration() {
        Connector connector = connector(1, "https://openapi-rdc.aliyuncs.com",
                "cipher-text", "{\"edition\":\"center\",\"organizationId\":\"org-1\"}");
        stubConnector(connector);
        stubExtra(connector, "edition", "center", null);
        stubExtra(connector, "organizationId", "org-1", null);
        when(registryService.credential(connector, "token")).thenReturn("pat-token");

        YunxiaoRuntimeConfig config = service.getRuntimeConfig();

        assertThat(config.enabled()).isTrue();
        assertThat(config.edition()).isEqualTo("center");
        assertThat(config.baseUrl()).isEqualTo("https://openapi-rdc.aliyuncs.com");
        assertThat(config.organizationId()).isEqualTo("org-1");
        assertThat(config.token()).isEqualTo("pat-token");
        assertThat(config.tokenSource()).isEqualTo("PAGE");
        assertThat(config.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("连接器行缺失时回落到环境变量，来源标记 ENVIRONMENT")
    void fallsBackToEnvironmentWhenConnectorMissing() {
        when(registryService.findByCode(ConnectorRegistryService.CODE_YUNXIAO)).thenReturn(Optional.empty());
        environment.setEnabled(true);
        environment.setEdition("center");
        environment.setBaseUrl("https://env.example.com");
        environment.setOrganizationId("env-org");
        environment.setToken("env-token");

        YunxiaoRuntimeConfig config = service.getRuntimeConfig();

        assertThat(config.enabled()).isTrue();
        assertThat(config.baseUrl()).isEqualTo("https://env.example.com");
        assertThat(config.organizationId()).isEqualTo("env-org");
        assertThat(config.token()).isEqualTo("env-token");
        assertThat(config.tokenSource()).isEqualTo("ENVIRONMENT");
    }

    @Test
    @DisplayName("连接器未配置令牌时使用环境变量令牌")
    void environmentTokenWinsWhenConnectorTokenBlank() {
        Connector connector = connector(1, "https://openapi-rdc.aliyuncs.com", null, "{\"organizationId\":\"org-1\"}");
        stubConnector(connector);
        stubExtra(connector, "edition", null, "center");
        stubExtra(connector, "organizationId", null, null);
        environment.setToken("env-token");

        YunxiaoRuntimeConfig config = service.getRuntimeConfig();

        assertThat(config.token()).isEqualTo("env-token");
        assertThat(config.tokenSource()).isEqualTo("ENVIRONMENT");
    }

    @Test
    @DisplayName("令牌无法解密时降级为可恢复的 CONFIG_ERROR，而不是抛异常")
    void unreadableTokenDegradesToConfigError() {
        Connector connector = connector(1, "https://openapi-rdc.aliyuncs.com", "broken-cipher",
                "{\"organizationId\":\"org-1\"}");
        connector.setLastTestStatus("FAILED");
        connector.setLastTestMessage("旧消息");
        stubConnector(connector);
        stubExtra(connector, "edition", null, "center");
        stubExtra(connector, "organizationId", null, null);
        when(registryService.credential(connector, "token"))
                .thenThrow(new IllegalStateException("云效令牌解密失败，请重新录入令牌"));

        YunxiaoRuntimeConfig config = service.getRuntimeConfig();

        assertThat(config.token()).isNull();
        assertThat(config.tokenSource()).isEqualTo("UNREADABLE");
        assertThat(config.lastTestStatus()).isEqualTo("CONFIG_ERROR");
        assertThat(config.lastTestMessage()).contains("连接器管理");
    }

    @Test
    @DisplayName("专有云版本不带组织 ID 也视为已配置，中心化版本必须有组织 ID")
    void editionDeterminesOrganizationRequirement() {
        Connector region = connector(1, "https://region.example.com", "cipher", "{\"edition\":\"region\"}");
        stubConnector(region);
        stubExtra(region, "edition", null, "region");
        stubExtra(region, "organizationId", null, null);
        when(registryService.credential(region, "token")).thenReturn("tok");

        assertThat(service.getRuntimeConfig().isConfigured()).isTrue();

        Connector center = connector(1, "https://center.example.com", "cipher", "{\"edition\":\"center\"}");
        stubConnector(center);
        stubExtra(center, "edition", null, "center");
        stubExtra(center, "organizationId", null, null);

        assertThat(service.getRuntimeConfig().isConfigured()).isFalse();
    }

    @Test
    @DisplayName("非法版本取值一律按中心化处理")
    void invalidEditionNormalizedToCenter() {
        Connector connector = connector(0, "https://center.example.com", null, "{\"edition\":\"weird\"}");
        stubConnector(connector);
        stubExtra(connector, "edition", "weird", null);
        stubExtra(connector, "organizationId", null, null);

        assertThat(service.getRuntimeConfig().edition()).isEqualTo("center");
    }
}
