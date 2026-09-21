package com.bu.management.vo;

import java.time.LocalDateTime;

/**
 * AI 助手附件视图
 *
 * @author BU Team
 * @since 2026-09-21
 */
public record AiAgentAttachmentVO(
        Long id,
        String fileName,
        String contentType,
        Long sizeBytes,
        LocalDateTime createdAt) {
}
