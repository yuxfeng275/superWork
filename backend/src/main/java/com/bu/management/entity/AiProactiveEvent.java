package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 主动性事件：邮件同步完成 / 会议纪要生成 / 周报生成失败等「有事发生」的留痕。
 *
 * <p>只记元数据，不记正文。事件本身不做业务处理，作用是两件事：
 * 证明某个建议是「事件触发」而不是「打开页面顺手扫的」，以及给触发频率上限留一个可查的依据。
 *
 * @author BU Team
 * @since 2026-11-17
 */
@Data
@TableName("ai_proactive_event")
public class AiProactiveEvent {

    /** 邮箱同步完成（含归档邮箱按收件人归属）。 */
    public static final String KIND_EMAIL_SYNCED = "EMAIL_SYNCED";

    /** 会议纪要生成完成。 */
    public static final String KIND_MEETING_MINUTES = "MEETING_MINUTES";

    /** 周报生成失败。 */
    public static final String KIND_WEEKLY_REPORT_FAILED = "WEEKLY_REPORT_FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String kind;

    private String refId;

    private LocalDateTime createdAt;
}
