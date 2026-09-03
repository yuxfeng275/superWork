package com.bu.management.vo;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * AI 助手工具定义（JSON Schema 原样透传给侧车）
 *
 * @author BU Team
 * @since 2026-09-03
 */
public record AiAgentToolDefinition(
        String name,
        String description,
        JsonNode parameters) {
}
