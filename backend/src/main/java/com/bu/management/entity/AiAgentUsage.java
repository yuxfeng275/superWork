package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 助手单轮用量：run_id 唯一，token 与成本按轮累计。
 *
 * @author BU Team
 * @since 2026-10-22
 */
@Data
@TableName("ai_agent_usage")
public class AiAgentUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;

    private Long sessionId;

    private Long userId;

    private String provider;

    private String model;

    private Long inputTokens;

    private Long outputTokens;

    private Long cacheReadTokens;

    private Long cacheWriteTokens;

    private Long totalTokens;

    private BigDecimal costUsd;

    private LocalDateTime createdAt;
}
