package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 助手主动建议：规则扫描生成，(user_id, dedupe_key) 唯一保证同一周期只提一次。
 *
 * @author BU Team
 * @since 2026-10-22
 */
@Data
@TableName("ai_proactive_suggestion")
public class AiProactiveSuggestion {

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_DISMISSED = "DISMISSED";
    /**
     * 条件自行消失（如周报已出草稿）时由系统收回，不是用户忽略。
     * 指标里必须与 DISMISSED 分开算，否则自愈的规则会被自己拖低采纳率。
     */
    public static final String STATUS_AUTO_DISMISSED = "AUTO_DISMISSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String kind;

    private String dedupeKey;

    private String title;

    private String body;

    private String prompt;

    private String targetPath;

    private Integer score;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
