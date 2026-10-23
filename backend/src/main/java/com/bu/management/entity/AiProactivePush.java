package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 主动建议的站外推送记录：(user_id, pushed_on) 唯一，保证每人每天最多一条。
 *
 * @author BU Team
 * @since 2026-10-22
 */
@Data
@TableName("ai_proactive_push")
public class AiProactivePush {

    public static final String CHANNEL_WECOM = "WECOM";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String channel;

    /** 本次涉及的建 id，逗号分隔 */
    private String suggestionIds;

    private String content;

    private String status;

    private String reason;

    private LocalDate pushedOn;

    private LocalDateTime createdAt;
}
