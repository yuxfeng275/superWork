package com.bu.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会议模块本地存储配置。
 * 模块开关 / ASR 连接参数运行时从 system_config 配置组 meeting 读取（见 MeetingConfigService），
 * LLM 凭据走连接器（AiAgentModelConfigService）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "meeting")
public class MeetingProperties {

    /**
     * 录音文件存储根目录（相对或绝对路径）
     */
    private String storageDir = "./data/meetings";
}
