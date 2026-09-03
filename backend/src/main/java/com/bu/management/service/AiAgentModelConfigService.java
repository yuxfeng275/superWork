package com.bu.management.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 助手 GLM（智谱）模型配置，读取通用系统配置组 ai-agent。
 * 与邮件摘要的 DeepSeek 配置相互独立：AI 助手只跑 GLM。
 */
@Service
public class AiAgentModelConfigService {

    public static final String GROUP = "ai-agent";

    private final SystemConfigService systemConfigService;

    public AiAgentModelConfigService(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    /**
     * 解析模型配置；未启用或未配置 API Key 时抛出
     * {@link IllegalStateException}（全局处理器转 400）。
     */
    public ModelConfig resolveModelConfig() {
        boolean enabled = systemConfigService.getBoolean(GROUP, "aiagent.enabled", false);
        String baseUrl = systemConfigService.getValue(GROUP, "aiagent.base-url",
                "https://open.bigmodel.cn/api/paas/v4");
        String model = systemConfigService.getValue(GROUP, "aiagent.model", "glm-5.3");
        String apiKey = systemConfigService.getValue(GROUP, "aiagent.api-key", null);
        if (!enabled || !StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("AI 模型未配置或未启用");
        }
        return new ModelConfig("zhipu", baseUrl, model, apiKey);
    }

    /**
     * 传递给侧车的模型配置；provider 固定 zhipu。
     */
    public record ModelConfig(String provider, String baseUrl, String model, String apiKey) {}
}
