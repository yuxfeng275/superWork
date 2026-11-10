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

    /** Prompt 编码（对应 ai_prompt.code），标识这次调用用的是哪段提示词。 */
    private String promptCode;

    /** Prompt 版本号（对应 ai_prompt.version），便于回溯「同一段提示词哪个版本更省/更准」。 */
    private Integer promptVersion;

    /** 单次调用耗时（毫秒）：从发起请求到返回/抛异常的墙钟时间，看板用它算平均耗时。 */
    private Long latencyMs;

    /** 关联业务任务 ID（如周报、会议纪要主键），可为空；用于把用量回挂到具体任务。 */
    private Long taskId;

    /** 调用结果状态：SUCCESS=成功返回，FAILED=抛异常（失败原因只进日志，不落库）。 */
    private String status;

    private LocalDateTime createdAt;
}
