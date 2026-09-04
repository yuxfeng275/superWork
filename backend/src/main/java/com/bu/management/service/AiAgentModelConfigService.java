package com.bu.management.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 助手模型配置，读取通用系统配置组 ai-agent。
 * 支持 GLM（智谱，key 前缀 aiagent.*）与 DeepSeek（key 前缀 aiagent.deepseek.*）；
 * 运行时按会话的 provider 取对应配置传给侧车。
 */
@Service
public class AiAgentModelConfigService {

    public static final String GROUP = "ai-agent";
    public static final String PROVIDER_ZHIPU = "zhipu";
    public static final String PROVIDER_DEEPSEEK = "deepseek";

    /** 前端可选模型：provider + 模型名 + 展示名。 */
    public record ModelOption(String provider, String model, String label) {}

    private final SystemConfigService systemConfigService;

    public AiAgentModelConfigService(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    /** 前端可选模型列表（仅返回已启用且配置了 API Key 的 provider）。 */
    public List<ModelOption> listAvailableModels() {
        if (isProviderReady(PROVIDER_ZHIPU, "aiagent.enabled", "aiagent.api-key")) {
            String model = systemConfigService.getValue(GROUP, "aiagent.model", "glm-5.3");
            if (StringUtils.hasText(model)) {
                return List.of(new ModelOption(PROVIDER_ZHIPU, model, "GLM（智谱）"));
            }
        }
        if (isProviderReady(PROVIDER_DEEPSEEK, "aiagent.deepseek.enabled", "aiagent.deepseek.api-key")) {
            String model = systemConfigService.getValue(GROUP, "aiagent.deepseek.model", "deepseek-v4-flash");
            if (StringUtils.hasText(model)) {
                return List.of(new ModelOption(PROVIDER_DEEPSEEK, model, "DeepSeek"));
            }
        }
        return List.of();
    }

    /**
     * 解析指定 provider 的模型配置；未启用或未配置 API Key 时抛出
     * {@link IllegalStateException}（全局处理器转 400）。
     */
    public ModelConfig resolveModelConfig(String provider) {
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            return resolve("aiagent.deepseek.enabled", "aiagent.deepseek.base-url",
                    "aiagent.deepseek.model", "aiagent.deepseek.api-key",
                    "https://api.deepseek.com", "deepseek-v4-flash");
        }
        return resolve("aiagent.enabled", "aiagent.base-url",
                "aiagent.model", "aiagent.api-key",
                "https://open.bigmodel.cn/api/paas/v4", "glm-5.3");
    }

    private ModelConfig resolve(String enabledKey, String baseUrlKey, String modelKey,
            String apiKeyKey, String defaultBaseUrl, String defaultModel) {
        boolean enabled = systemConfigService.getBoolean(GROUP, enabledKey, false);
        String baseUrl = systemConfigService.getValue(GROUP, baseUrlKey, defaultBaseUrl);
        String model = systemConfigService.getValue(GROUP, modelKey, defaultModel);
        String apiKey = systemConfigService.getValue(GROUP, apiKeyKey, null);
        if (!enabled || !StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("AI 模型未配置或未启用");
        }
        return new ModelConfig(baseUrl, model, apiKey);
    }

    private boolean isProviderReady(String provider, String enabledKey, String apiKeyKey) {
        boolean enabled = systemConfigService.getBoolean(GROUP, enabledKey, false);
        String apiKey = systemConfigService.getValue(GROUP, apiKeyKey, null);
        return enabled && StringUtils.hasText(apiKey)
                && (PROVIDER_DEEPSEEK.equals(provider)
                    ? StringUtils.hasText(systemConfigService.getValue(GROUP, "aiagent.deepseek.model", null))
                    : StringUtils.hasText(systemConfigService.getValue(GROUP, "aiagent.model", null)));
    }

    /**
     * 传递给侧车的模型配置；provider 在会话上维护。
     */
    public record ModelConfig(String baseUrl, String model, String apiKey) {}
}
