package com.bu.management.vo;

/**
 * AI 助手工具执行结果；content 为给模型的中文简述文本
 *
 * @author BU Team
 * @since 2026-09-03
 */
public record AiAgentToolResult(String content, boolean isError) {
}
