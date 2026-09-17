package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.entity.AiModel;
import com.bu.management.entity.Connector;
import com.bu.management.mapper.AiModelMapper;
import java.util.List;
import java.util.Optional;
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
 * 模型管理核心契约：可用性判定、提供方归一、默认模型选择、摘要模型选择。
 * 连接器只提供地址与凭据，模型表决定用哪个模型、给谁用。
 */
@ExtendWith(MockitoExtension.class)
class AiModelConfigServiceTest {

    @Mock
    private AiModelMapper mapper;
    @Mock
    private ConnectorRegistryService registryService;

    private AiModelConfigService service;

    @BeforeEach
    void setUp() {
        service = new AiModelConfigService(mapper, registryService);
    }

    private AiModel model(Long id, String provider, String name, int assistant, int digest, int isDefault, int enabled,
                          int sortOrder) {
        AiModel entity = new AiModel();
        entity.setId(id);
        entity.setProviderCode(provider);
        entity.setModel(name);
        entity.setDisplayName(name);
        entity.setAssistantEnabled(assistant);
        entity.setDigestEnabled(digest);
        entity.setIsDefault(isDefault);
        entity.setEnabled(enabled);
        entity.setSortOrder(sortOrder);
        return entity;
    }

    private Connector readyConnector(String code, String baseUrl) {
        Connector connector = new Connector();
        connector.setCode(code);
        connector.setName(code);
        connector.setBaseUrl(baseUrl);
        connector.setEnabled(1);
        connector.setEncryptedToken("cipher");
        return connector;
    }

    private void stubConnector(Connector connector, String status) {
        lenient().when(registryService.findByCode(connector.getCode())).thenReturn(Optional.of(connector));
        lenient().when(registryService.status(connector)).thenReturn(status);
        lenient().when(registryService.credential(connector, "token")).thenReturn("sk-plain");
    }

    @Test
    @DisplayName("提供方归一：历史 zhipu 视为 glm 连接器")
    void normalizesLegacyZhipuProvider() {
        assertThat(service.normalizeProvider("zhipu")).isEqualTo("glm");
        assertThat(service.normalizeProvider("DEEPSEEK")).isEqualTo("deepseek");
        assertThat(service.normalizeProvider(null)).isEqualTo("glm");
    }

    @Test
    @DisplayName("助手可选模型：仅返回启用、勾选助手、且提供方连接器就绪的模型，默认模型排在最前")
    void listAvailableModelsFiltersByReadinessAndPurpose() {
        Connector deepseek = readyConnector("deepseek", "https://api.deepseek.com");
        Connector glm = readyConnector("glm", "https://open.bigmodel.cn/api/paas/v4");
        stubConnector(deepseek, "READY");
        stubConnector(glm, "NOT_CONFIGURED");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                model(1L, "deepseek", "deepseek-v4-flash", 1, 1, 1, 1, 10),
                model(2L, "glm", "glm-5.3", 1, 0, 0, 1, 20),
                model(3L, "deepseek", "deepseek-chat", 0, 0, 0, 1, 30),
                model(4L, "deepseek", "deepseek-old", 1, 0, 0, 0, 40)));

        List<AiModelConfigService.ModelOption> options = service.listAvailableModels();

        assertThat(options).hasSize(1);
        assertThat(options.get(0).provider()).isEqualTo("deepseek");
        assertThat(options.get(0).model()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    @DisplayName("助手运行参数：会话指定模型优先，其次默认模型；地址与凭据来自连接器")
    void resolveModelConfigPrefersSessionModelThenDefault() {
        Connector deepseek = readyConnector("deepseek", "https://api.deepseek.com");
        stubConnector(deepseek, "READY");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                model(1L, "deepseek", "deepseek-v4-flash", 1, 1, 1, 1, 10),
                model(2L, "deepseek", "deepseek-chat", 1, 0, 0, 1, 20)));

        AiModelConfigService.ModelConfig explicit = service.resolveModelConfig("deepseek", "deepseek-chat");
        assertThat(explicit.model()).isEqualTo("deepseek-chat");
        assertThat(explicit.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(explicit.apiKey()).isEqualTo("sk-plain");

        AiModelConfigService.ModelConfig fallback = service.resolveModelConfig("deepseek", null);
        assertThat(fallback.model()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    @DisplayName("提供方连接器未就绪时，解析明确报错并指向连接器管理")
    void resolveFailsWhenProviderNotReady() {
        Connector deepseek = readyConnector("deepseek", "https://api.deepseek.com");
        stubConnector(deepseek, "NOT_CONFIGURED");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                model(1L, "deepseek", "deepseek-v4-flash", 1, 1, 1, 1, 10)));

        assertThatThrownBy(() -> service.resolveModelConfig("deepseek", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("连接器管理");
    }

    @Test
    @DisplayName("未配置任何可用模型时给出可操作提示")
    void resolveFailsWhenNoModelConfigured() {
        Connector deepseek = readyConnector("deepseek", "https://api.deepseek.com");
        when(registryService.findByCode("deepseek")).thenReturn(Optional.of(deepseek));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveModelConfig("deepseek", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型管理");
    }

    @Test
    @DisplayName("摘要模型：取第一条勾选摘要且提供方就绪的模型")
    void digestModelPicksFirstReadyDigestCandidate() {
        Connector deepseek = readyConnector("deepseek", "https://api.deepseek.com");
        Connector glm = readyConnector("glm", "https://open.bigmodel.cn/api/paas/v4");
        stubConnector(deepseek, "READY");
        stubConnector(glm, "DISABLED");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                model(2L, "glm", "glm-5.3", 1, 1, 0, 1, 10),
                model(1L, "deepseek", "deepseek-v4-flash", 1, 1, 1, 1, 20)));

        AiModelConfigService.DigestModel digest = service.digestModel().orElseThrow();

        assertThat(digest.providerCode()).isEqualTo("deepseek");
        assertThat(digest.model()).isEqualTo("deepseek-v4-flash");
        assertThat(digest.apiKey()).isEqualTo("sk-plain");
    }

    @Test
    @DisplayName("摘要模型：没有勾选摘要的模型时不返回（摘要回落规则生成）")
    void digestModelEmptyWhenNoneSelected() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                model(1L, "deepseek", "deepseek-v4-flash", 1, 0, 1, 1, 10)));

        assertThat(service.digestModel()).isEmpty();
    }

    @Test
    @DisplayName("默认模型：取标记默认且可用的模型")
    void defaultModelReturnsFlaggedRow() {
        Connector glm = readyConnector("glm", "https://open.bigmodel.cn/api/paas/v4");
        stubConnector(glm, "READY");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                model(1L, "deepseek", "deepseek-v4-flash", 1, 1, 0, 1, 10),
                model(2L, "glm", "glm-5.3", 1, 0, 1, 1, 20)));

        AiModelConfigService.ModelOption defaultModel = service.defaultModel().orElseThrow();

        assertThat(defaultModel.provider()).isEqualTo("glm");
        assertThat(defaultModel.model()).isEqualTo("glm-5.3");
    }
}
