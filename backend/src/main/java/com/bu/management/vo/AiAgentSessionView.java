package com.bu.management.vo;

import java.time.LocalDateTime;

/**
 * AI 助手会话完整视图；messages 为解析后的 JSON 数组原样透传
 *
 * @author BU Team
 * @since 2026-09-03
 */
public record AiAgentSessionView(
        Long id,
        String title,
        String provider,
        String model,
        LocalDateTime updatedAt,
        Object messages) {
}
