package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
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

    /** 随消息引用的附件 ID（须为本会话已上传附件，最多 5 个） */
    @Size(max = 5, message = "单条消息最多引用 5 个附件")
    private List<Long> attachmentIds;
}
