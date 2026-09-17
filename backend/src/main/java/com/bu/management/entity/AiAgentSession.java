package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 助手会话
 *
 * @author BU Team
 * @since 2026-09-03
 */
@Data
@TableName("ai_agent_session")
public class AiAgentSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 归属用户
     */
    private Long ownerUserId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 模型提供方，固定 zhipu
     */
    private String provider;

    /**
     * 模型名称，固定 glm-5.3
     */
    private String model;

    /**
     * AgentMessage JSON 数组，旧→新
     */
    private String messagesJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
