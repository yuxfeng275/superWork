package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 助手会话附件（本地落盘，发送时随消息注入文本摘要）
 *
 * @author BU Team
 * @since 2026-09-21
 */
@Data
@TableName("ai_agent_attachment")
public class AiAgentAttachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long ownerUserId;

    private String fileName;

    private String contentType;

    private Long sizeBytes;

    private String sha256;

    /** 存储相对路径（会话子目录下） */
    private String relativePath;

    private LocalDateTime createdAt;
}
