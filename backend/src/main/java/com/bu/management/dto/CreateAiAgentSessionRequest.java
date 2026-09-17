package com.bu.management.dto;

import lombok.Data;

/**
 * 创建 AI 助手会话请求
 *
 * @author BU Team
 * @since 2026-09-03
 */
@Data
public class CreateAiAgentSessionRequest {

    private String title;

    /**
     * zhipu（GLM）或 deepseek；缺省 zhipu
     */
    private String provider;

    /**
     * 模型名称，如 glm-5.3 / deepseek-v4-flash；缺省用系统配置
     */
    private String model;
}
