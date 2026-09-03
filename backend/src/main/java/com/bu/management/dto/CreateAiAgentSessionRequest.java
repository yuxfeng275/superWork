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
     * 仅支持 zhipu
     */
    private String provider;

    /**
     * 仅支持 glm-5.3
     */
    private String model;
}
