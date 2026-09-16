package com.bu.management.service;

import com.bu.management.entity.Connector;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 助手模型配置：模型凭据与地址统一取自连接器注册表（code=glm / deepseek），
 * 优先返回 GLM，其次 DeepSeek；模型名存于连接器 extra_config.model。
 *
 * @author BU Team
 */
@Service
@RequiredArgsConstructor
public class AiAgentModelConfigService {

    public static final String PROVIDER_ZHIPU = "zhipu";
    public static final String PROVIDER_DEEPSEEK = "deepseek";

    private final ConnectorRegistryService registryService;

    /** 前端可选模型：provider + 模型名 + 展示名。 */
    public record ModelOption(String provider, String model, String label) {}

    /** 传递给侧车的模型配置；provider 在会话上维护。 */
    public record ModelConfig(String baseUrl, String model, String apiKey) {}

    /** 前端可选模型列表（优先 GLM，其次 DeepSeek，仅在连接器启用且配置完整时返回）。 */
    public List<ModelOption> listAvailableModels() {
        Optional<ModelConfig> zhipu = readyConfig(ConnectorRegistryService.CODE_GLM);
        if (zhipu.isPresent()) {
            return List.of(new ModelOption(PROVIDER_ZHIPU, zhipu.get().model(), "GLM（智谱）"));
        }
        Optional<ModelConfig> deepSeek = readyConfig(ConnectorRegistryService.CODE_DEEPSEEK);
        if (deepSeek.isPresent()) {
            return List.of(new ModelOption(PROVIDER_DEEPSEEK, deepSeek.get().model(), "DeepSeek"));
        }
        return List.of();
    }

    /**
     * 解析指定 provider 的模型配置；未启用或未配置 API Key 时抛出
     * {@link IllegalStateException}（全局处理器转 400）。
     */
    public ModelConfig resolveModelConfig(String provider) {
        String code = PROVIDER_DEEPSEEK.equals(provider)
                ? ConnectorRegistryService.CODE_DEEPSEEK : ConnectorRegistryService.CODE_GLM;
        return readyConfig(code)
                .orElseThrow(() -> new IllegalStateException("AI 模型未配置或未启用，请在「连接器管理」配置 GLM 或 DeepSeek"));
    }

    private Optional<ModelConfig> readyConfig(String code) {
        Connector connector = registryService.findByCode(code).orElse(null);
        if (connector == null || !"READY".equals(registryService.status(connector))) {
            return Optional.empty();
        }
        String defaultModel = ConnectorRegistryService.CODE_DEEPSEEK.equals(code) ? "deepseek-v4-flash" : "glm-5.3";
        String model = registryService.extra(connector, "model", defaultModel);
        String apiKey = registryService.credential(connector, "token");
        if (!StringUtils.hasText(model) || !StringUtils.hasText(apiKey)) {
            return Optional.empty();
        }
        return Optional.of(new ModelConfig(connector.getBaseUrl(), model, apiKey));
    }
}
