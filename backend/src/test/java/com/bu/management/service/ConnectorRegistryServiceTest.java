package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.entity.Connector;
import com.bu.management.mapper.ConnectorMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 连接器注册表核心契约：就绪状态判定、启用前校验、扩展参数合并。
 * 这些口径是连接器管理页、AI 助手面板、各业务域（同步/工具）的共同依赖。
 */
@ExtendWith(MockitoExtension.class)
class ConnectorRegistryServiceTest {

    @Mock
    private ConnectorMapper mapper;
    @Mock
    private EmailCredentialCipher cipher;

    private ConnectorRegistryService service;

    @BeforeEach
    void setUp() {
        service = new ConnectorRegistryService(mapper, cipher, new ObjectMapper());
    }

    private Connector connector(String code, String authType, Integer enabled) {
        Connector connector = new Connector();
        connector.setId(1L);
        connector.setCode(code);
        connector.setName(code);
        connector.setAuthType(authType);
        connector.setEnabled(enabled);
        connector.setBaseUrl("https://example.com");
        return connector;
    }

    @Test
    @DisplayName("状态：未启用=DISABLED；启用但缺凭据=NOT_CONFIGURED；启用且完整=READY")
    void statusReflectsEnabledAndCredentials() {
        Connector disabled = connector("worktime", ConnectorRegistryService.AUTH_BASIC, 0);
        assertThat(service.status(disabled)).isEqualTo("DISABLED");

        Connector enabledMissing = connector("worktime", ConnectorRegistryService.AUTH_BASIC, 1);
        assertThat(service.status(enabledMissing)).isEqualTo("NOT_CONFIGURED");

        Connector enabledReady = connector("worktime", ConnectorRegistryService.AUTH_BASIC, 1);
        enabledReady.setEncryptedUsername("u");
        enabledReady.setEncryptedPassword("p");
        assertThat(service.status(enabledReady)).isEqualTo("READY");
    }

    @Test
    @DisplayName("状态：中心化云效缺组织 ID 视为未配置，专有云不要求组织 ID")
    void yunxiaoRequiresOrganizationIdOnlyForCenterEdition() {
        Connector center = connector("yunxiao", ConnectorRegistryService.AUTH_TOKEN, 1);
        center.setEncryptedToken("t");
        center.setExtraConfig("{\"edition\":\"center\"}");
        assertThat(service.status(center)).isEqualTo("NOT_CONFIGURED");

        center.setExtraConfig("{\"edition\":\"center\",\"organizationId\":\"org\"}");
        assertThat(service.status(center)).isEqualTo("READY");

        Connector region = connector("yunxiao", ConnectorRegistryService.AUTH_TOKEN, 1);
        region.setEncryptedToken("t");
        region.setExtraConfig("{\"edition\":\"region\"}");
        assertThat(service.status(region)).isEqualTo("READY");
    }

    @Test
    @DisplayName("启用校验：缺凭据时开启连接器被拒绝，且提示缺失项")
    void enablingIncompleteConnectorIsRejected() {
        Connector stored = connector("yuque", ConnectorRegistryService.AUTH_MCP, 0);
        when(mapper.selectById(1L)).thenReturn(stored);

        ConnectorRegistryService.ConnectorSaveRequest request = new ConnectorRegistryService.ConnectorSaveRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, Boolean.TRUE, null);

        assertThatThrownBy(() -> service.update(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("启用前必须补全连接配置");
    }

    @Test
    @DisplayName("启用校验：凭据齐备的更新可以开启连接器")
    void enablingCompleteConnectorSucceeds() {
        Connector stored = connector("yuque", ConnectorRegistryService.AUTH_MCP, 0);
        stored.setMcpUrl("https://mcp.example.com/mcp");
        stored.setEncryptedToken("token-cipher");
        when(mapper.selectById(1L)).thenReturn(stored);
        when(mapper.updateById(any(Connector.class))).thenAnswer(invocation -> {
            Connector patch = invocation.getArgument(0);
            stored.setEnabled(patch.getEnabled() == null ? stored.getEnabled() : patch.getEnabled());
            stored.setExtraConfig(patch.getExtraConfig() == null ? stored.getExtraConfig() : patch.getExtraConfig());
            return 1;
        });

        ConnectorRegistryService.ConnectorSaveRequest request = new ConnectorRegistryService.ConnectorSaveRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, Boolean.TRUE, null);

        ConnectorRegistryService.ConnectorView view = service.update(1L, request);

        assertThat(view.enabled()).isTrue();
        assertThat(view.ready()).isTrue();
    }

    @Test
    @DisplayName("扩展参数：部分更新只覆盖给定键，空串删除键")
    void extraConfigMergesPartialUpdates() {
        Connector stored = connector("deepseek", ConnectorRegistryService.AUTH_TOKEN, 1);
        stored.setExtraConfig("{\"model\":\"deepseek-v4-flash\",\"digestModel\":\"deepseek-chat\"}");
        when(mapper.selectById(1L)).thenReturn(stored);
        when(mapper.updateById(any(Connector.class))).thenAnswer(invocation -> {
            stored.setExtraConfig(invocation.<Connector>getArgument(0).getExtraConfig());
            return 1;
        });

        ConnectorRegistryService.ConnectorSaveRequest request = new ConnectorRegistryService.ConnectorSaveRequest(
                null, null, null, null, null, null, null, null,
                Map.of("model", "deepseek-v4-flash2", "digestModel", ""), null, null, null, null, null, null);

        service.update(1L, request);

        assertThat(service.extraMap(stored)).containsEntry("model", "deepseek-v4-flash2")
                .doesNotContainKey("digestModel");
    }

    @Test
    @DisplayName("扩展参数：非法键被拒绝")
    void extraConfigRejectsInvalidKey() {
        Connector stored = connector("glm", ConnectorRegistryService.AUTH_TOKEN, 1);
        when(mapper.selectById(1L)).thenReturn(stored);

        ConnectorRegistryService.ConnectorSaveRequest request = new ConnectorRegistryService.ConnectorSaveRequest(
                null, null, null, null, null, null, null, null,
                Map.of("bad key!", "x"), null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("扩展参数键不合法");
    }

    @Test
    @DisplayName("状态列表：内置连接器齐全且带就绪提示")
    void statusesCoverBuiltInConnectors() {
        lenient().when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                readyWorktime(), connector("oa", ConnectorRegistryService.AUTH_SEEYON, 0)));

        List<ConnectorRegistryService.ConnectorStatus> statuses = service.statuses();

        assertThat(statuses).extracting(ConnectorRegistryService.ConnectorStatus::code)
                .containsExactly("worktime", "oa");
        assertThat(statuses.get(0).status()).isEqualTo("READY");
        assertThat(statuses.get(0).hint()).contains("工时");
        assertThat(statuses.get(1).status()).isEqualTo("DISABLED");
        assertThat(statuses.get(1).hint()).contains("连接器管理");
    }

    private Connector readyWorktime() {
        Connector connector = connector("worktime", ConnectorRegistryService.AUTH_BASIC, 1);
        connector.setEncryptedUsername("u");
        connector.setEncryptedPassword("p");
        return connector;
    }

    @Test
    @DisplayName("企业微信：仅机器人通道（Bot ID + Bot Secret）配置完整即可就绪")
    void wecomReadyWithBotChannelOnly() {
        Connector botOnly = connector("wecom", ConnectorRegistryService.AUTH_WECOM, 1);
        botOnly.setExtraConfig("{\"botId\":\"aibXXX\"}");
        botOnly.setEncryptedBotSecret("encrypted");

        assertThat(service.status(botOnly)).isEqualTo("READY");
        assertThat(service.hint(botOnly)).contains("机器人通道");
    }

    @Test
    @DisplayName("企业微信：只有 Bot ID 没 Secret 时提示两者需同时填写")
    void wecomRequiresBotSecretWithBotId() {
        Connector halfConfigured = connector("wecom", ConnectorRegistryService.AUTH_WECOM, 1);
        halfConfigured.setExtraConfig("{\"botId\":\"aibXXX\"}");

        assertThat(service.status(halfConfigured)).isEqualTo("NOT_CONFIGURED");
        assertThat(service.hint(halfConfigured)).contains("Bot Secret");
    }

    @Test
    @DisplayName("企业微信：应用通道完整（Secret/CorpId/AgentId）也可就绪，提示引导配置机器人通道")
    void wecomReadyWithAppChannel() {
        Connector appOnly = connector("wecom", ConnectorRegistryService.AUTH_WECOM, 1);
        appOnly.setEncryptedToken("encrypted-secret");
        appOnly.setExtraConfig("{\"corpId\":\"corp\",\"agentId\":\"1000002\"}");

        assertThat(service.status(appOnly)).isEqualTo("READY");
        assertThat(service.hint(appOnly)).contains("应用通道已就绪").contains("机器人通道未配置");
    }

    @Test
    @DisplayName("企业微信：两个通道都没配置时给出双通道缺失提示")
    void wecomRequiresAtLeastOneChannel() {
        Connector empty = connector("wecom", ConnectorRegistryService.AUTH_WECOM, 1);

        assertThat(service.status(empty)).isEqualTo("NOT_CONFIGURED");
        assertThat(service.hint(empty)).contains("应用通道").contains("机器人通道");
    }

    @Test
    @DisplayName("企业微信：Bot Secret 走独立加密列，不与应用 Secret 混用")
    void wecomBotSecretStoredSeparately() {
        Connector connector = connector("wecom", ConnectorRegistryService.AUTH_WECOM, 1);
        connector.setEncryptedToken("app-secret-cipher");
        connector.setEncryptedBotSecret("bot-secret-cipher");
        when(cipher.decrypt("app-secret-cipher")).thenReturn("app-secret");
        when(cipher.decrypt("bot-secret-cipher")).thenReturn("bot-secret");

        assertThat(service.credential(connector, "token")).isEqualTo("app-secret");
        assertThat(service.credential(connector, "botSecret")).isEqualTo("bot-secret");
    }
}
