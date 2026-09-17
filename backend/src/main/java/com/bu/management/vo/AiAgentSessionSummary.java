package com.bu.management.vo;

import java.time.LocalDateTime;

/**
 * AI 助手会话摘要项
 *
 * @author BU Team
 * @since 2026-09-03
 */
public record AiAgentSessionSummary(
        Long id,
        String title,
        String provider,
        String model,
        LocalDateTime updatedAt,
        int messageCount) {
}
