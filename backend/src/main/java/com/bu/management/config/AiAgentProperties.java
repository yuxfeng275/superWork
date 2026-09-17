package com.bu.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 助手侧车（Pi Agent sidecar）连接配置。
 * 模型凭据不在这里：GLM（智谱）配置走 system_config 通用配置组 ai-agent。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-agent.sidecar")
public class AiAgentProperties {

    /**
     * 侧车根地址
     */
    private String url = "http://localhost:8787";

    /**
     * 侧车访问令牌，空表示不校验（开发环境）
     */
    private String token = "";

    /**
     * 单轮运行超时（秒）
     */
    private int runTimeoutSeconds = 600;

    /**
     * 侧车回调 Java 后端执行工具的地址
     */
    private String toolCallbackUrl = "http://localhost:8080/internal/ai-agent/tools";
}
