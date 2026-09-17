package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 助手发送消息请求
 *
 * @author BU Team
 * @since 2026-09-03
 */
@Data
public class AiAgentSendMessageRequest {

    @NotBlank(message = "消息内容不能为空")
    private String content;
}
