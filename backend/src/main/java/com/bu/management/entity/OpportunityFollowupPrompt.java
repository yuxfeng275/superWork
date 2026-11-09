package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 商机跟进问句台账：调度器每天针对待跟进商机落一条问句，记录下来好回答与溯源。
 *
 * <p>{@code dedupe_key} 唯一（{@code opp:商机ID:阶段:yyyy-MM-dd}），保证同一商机同阶段每天只问一次。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Data
@TableName("opportunity_followup_prompt")
public class OpportunityFollowupPrompt {

    /** 已发出，等回复。 */
    public static final String STATE_SENT = "SENT";
    /** 已回复但没解析出结构化结果。 */
    public static final String STATE_REPLIED = "REPLIED";
    /** 已回复且解析出阶段/概率/下次跟进。 */
    public static final String STATE_PARSED = "PARSED";
    /** 人工确认落库。 */
    public static final String STATE_CONFIRMED = "CONFIRMED";
    /** 用户静音或过期未回复。 */
    public static final String STATE_EXPIRED = "EXPIRED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long opportunityId;

    /** 目标用户 ID（商机负责人）；为空表示没匹配上人，只留档不外发。 */
    private Long targetUserId;

    /**
     * 实际发送目标的企微 userid（推送成功才回写，站内兜底为空）。
     *
     * <p>口径：<b>试点期一律发创建人</b>，所以这里记的是创建人（于峰）的 wecom userid，
     * 而不是商机负责人的；正式期改为商机负责人后，这里随之变回负责人。
     */
    private String wecomUserid;

    /** 触发这条问句的提醒阶段（今日跟进 / 逾期跟进 / 静默唤醒 / 报价静默）。 */
    private String remindStage;

    /** 下发渠道：INAPP（站内兜底） / WECOM_APP（企微应用消息） / WECOM_BOT（试点期企微机器人单聊）。 */
    private String channel;

    private String questionText;

    private LocalDateTime sentAt;

    private LocalDateTime repliedAt;

    private String replyText;

    /** 解析结果 JSON（阶段/概率/下次跟进/摘要/置信度）。 */
    private String parsedJson;

    private String state;

    private String dedupeKey;

    private LocalDateTime createdAt;
}
