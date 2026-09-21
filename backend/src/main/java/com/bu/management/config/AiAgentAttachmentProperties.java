package com.bu.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 助手附件本地存储配置（对齐会议录音的落盘模式）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-agent.attachments")
public class AiAgentAttachmentProperties {

    /** 附件存储根目录（相对或绝对路径） */
    private String storageDir = "./data/ai-agent-attachments";

    /** 单文件大小上限（MB），与全局 multipart 上限取较小值 */
    private int maxSizeMb = 20;
}
