package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.entity.OpportunityFollowupPrompt;
import com.bu.management.entity.Quotation;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.entity.User;
import com.bu.management.integration.WeComClient;
import com.bu.management.mapper.EmailWeComMappingMapper;
import com.bu.management.mapper.OpportunityFollowupPromptMapper;
import com.bu.management.mapper.QuotationMapper;
import com.bu.management.mapper.SalesOpportunityFollowUpMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.bu.management.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 商机跟进闭环：问句生成 → 企微/站内下发 → 回复解析 → 自动落库（阶段仍由人工改）。
 *
 * <p>口径：
 * <ul>
 *   <li>问句只带商机名（截 12 字）、阶段与时间，<b>不带金额与毛利</b>；</li>
 *   <li>落库优先：先写 {@code opportunity_followup_prompt}（渠道 INAPP 兜底），再尝试企微推送，
 *       企微任何异常都吞掉，不影响落库与其它商机；</li>
 *   <li>幂等：{@code dedupe_key = opp:商机ID:阶段:yyyy-MM-dd}，撞唯一键就返回已有行；</li>
 *   <li>阶段推进复用 {@code SalesOpportunityService.ensureStageAllowed} 的语义（该方法为 private，
 *       这里复刻同一套判定），违反时抛业务异常而不是静默失败。</li>
 * </ul>
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpportunityFollowUpService {

    /** 站内兜底渠道（落库默认值）。 */
    public static final String CHANNEL_INAPP = "INAPP";
    /** 企微应用消息渠道。 */
    public static final String CHANNEL_WECOM_APP = "WECOM_APP";
    /** 企微机器人单聊渠道（试点期首选）。 */
    public static final String CHANNEL_WECOM_BOT = "WECOM_BOT";
    /** 企微问句确认落库的跟进记录来源。 */
    public static final String SOURCE_WECOM_BOT = "WECOM_BOT";
    /** 手工/AI 工具录入的跟进记录来源。 */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 采纳商机 AI 教练建议落库的跟进记录来源。 */
    public static final String SOURCE_AI_COACH = "AI_COACH";

    /** 提醒阶段标签。 */
    public static final String STAGE_DUE = "今日跟进";
    public static final String STAGE_OVERDUE = "逾期跟进";
    public static final String STAGE_SILENT = "静默唤醒";
    public static final String STAGE_QUOTATION_SILENT = "报价静默";

    /** 进入这两个阶段必须已有已批准的报价单（与 SalesOpportunityService 保持一致）。 */
    private static final List<String> STAGES_REQUIRING_APPROVAL = List.of("商务谈判", "已成交");
    /** 「我的商机」列表口径：已收口的商机不再催跟进（列表仍保留已流失/暂停推进供复盘）。 */
    private static final List<String> CLOSED_STATUSES = List.of("已成交", "已输单", "已关闭");
    /**
     * 提醒类查询（今日应跟 / 已逾期 / 静默 / 报价静默）统一排除的终态阶段。
     *
     * <p>权威阶段名单取自 {@code SalesOpportunityLinkService.STAGE_ORDER} 与前端商机筛选项：
     * 初步接触 / 需求确认 / 方案报价 / 商务谈判 / 暂停推进 / 已成交 / 已流失。其中：
     * <ul>
     *   <li>已成交 / 已输单 / 已关闭 / 已流失：跟进已终止，再发问句就是骚扰已流失商机；</li>
     *   <li><b>暂停推进 = 别打扰</b>：客户明说缓一缓，催办只会掉分；等人工改回进行中再提醒。</li>
     * </ul>
     * 名单写死在这里而不是复用 {@code CLOSED_STATUSES}：列表视图还要看已流失/暂停推进的商机。
     */
    static final List<String> REMINDER_EXCLUDED_STATUSES =
            List.of("已成交", "已流失", "已输单", "已关闭", "暂停推进");
    /** 报价静默关注的阶段。 */
    private static final List<String> QUOTATION_STAGES = List.of("方案报价", "商务谈判");
    /** 回复只认 24 小时内已发送问句。 */
    private static final long REPLY_WINDOW_HOURS = 24;
    /**
     * 引用匹配的回看窗口（天）：同一个商机要多次追进展，用户可能引用<b>已经回复过</b>的问句再回一次，
     * 那条台账此刻是 PARSED/CONFIRMED，若候选集只取 SENT 就必然匹配不到。
     */
    private static final long REPLY_QUOTED_WINDOW_DAYS = 7;
    /**
     * 同一人 7 天内可能收到多条问句（一次调度可同时给多个商机发，同一商机还会反复追）：
     * 候选最多取 20 条，再靠引用内容在里面挑出被回复的那一条。
     */
    private static final int REPLY_CANDIDATE_LIMIT = 20;
    /** 再次回复已回复问句时，克隆台账的阶段标签。 */
    private static final String STAGE_FOLLOWUP_REPLY = "跟进回复";
    private static final int MAX_NAME = 12;
    private static final DateTimeFormatter NEXT_TEXT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter DEDUPE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 系统配置组：商机跟进。 */
    private static final String CONFIG_GROUP = "opportunity";
    /**
     * 自动落库开关，<b>默认开</b>：企微回复解析出成交概率或下次跟进时间就直接写跟进记录 + 刷新商机，
     * 不再等人去「待确认」里点一次。置 false 才回到「只记原文、人工确认」。
     */
    private static final String AUTO_CONFIRM_KEY = "followup.auto-confirm";

    private final SalesOpportunityMapper opportunityMapper;
    private final SalesOpportunityFollowUpMapper followUpMapper;
    private final OpportunityFollowupPromptMapper promptMapper;
    private final QuotationMapper quotationMapper;
    private final EmailWeComMappingMapper weComMappingMapper;
    private final UserMapper userMapper;
    private final WeComClient weComClient;
    private final ObjectMapper objectMapper;
    private final SystemConfigService systemConfigService;
    /**
     * 商机 AI 教练（单向依赖：回复入站后入队一次生成）。
     *
     * <p>教练那边<b>不</b>注入本类，所以这里不存在循环依赖；入队失败也绝不能影响回复落库。
     */
    private final OpportunityCoachService coachService;

    // ==================== 查询 ====================

    /** 今日该跟进：下次跟进时间落在 today 当天。 */
    public List<SalesOpportunity> dueToday(LocalDate today) {
        LocalDate day = today == null ? LocalDate.now() : today;
        return opportunityMapper.selectList(open()
                .ge(SalesOpportunity::getNextFollowUpAt, day.atStartOfDay())
                .lt(SalesOpportunity::getNextFollowUpAt, day.plusDays(1).atStartOfDay())
                .orderByAsc(SalesOpportunity::getNextFollowUpAt));
    }

    /** 逾期：下次跟进时间早于今天 0 点。 */
    public List<SalesOpportunity> overdue(LocalDate today) {
        LocalDate day = today == null ? LocalDate.now() : today;
        return opportunityMapper.selectList(open()
                .lt(SalesOpportunity::getNextFollowUpAt, day.atStartOfDay())
                .orderByAsc(SalesOpportunity::getNextFollowUpAt));
    }

    /** 静默：超过 days 天没有任何跟进记录。 */
    public List<SalesOpportunity> silent(int days) {
        LocalDateTime before = LocalDateTime.now().minusDays(Math.max(1, days));
        return opportunityMapper.selectList(open()
                .isNotNull(SalesOpportunity::getLastFollowUpAt)
                .lt(SalesOpportunity::getLastFollowUpAt, before)
                .orderByAsc(SalesOpportunity::getLastFollowUpAt));
    }

    /** 报价静默：已进入报价阶段、报价发出后 days 天没有任何动静。 */
    public List<SalesOpportunity> quotationSilent(int days) {
        LocalDateTime before = LocalDateTime.now().minusDays(Math.max(1, days));
        return opportunityMapper.selectList(open()
                .in(SalesOpportunity::getStatus, QUOTATION_STAGES)
                .isNotNull(SalesOpportunity::getLastQuotationAt)
                .lt(SalesOpportunity::getLastQuotationAt, before)
                .orderByAsc(SalesOpportunity::getLastQuotationAt));
    }

    /** 未收口（含暂停推进） + 未静音的商机查询基座：提醒类查询全部走这里。 */
    private LambdaQueryWrapper<SalesOpportunity> open() {
        return new LambdaQueryWrapper<SalesOpportunity>()
                .eq(SalesOpportunity::getRemindMuted, 0)
                .notIn(SalesOpportunity::getStatus, REMINDER_EXCLUDED_STATUSES);
    }

    public SalesOpportunity get(Long id) {
        if (id == null) {
            throw new RuntimeException("商机不存在");
        }
        SalesOpportunity opportunity = opportunityMapper.selectById(id);
        if (opportunity == null) {
            throw new RuntimeException("商机不存在");
        }
        return opportunity;
    }

    /** 商机的问句台账（最近的在前）。 */
    public List<OpportunityFollowupPrompt> listPrompts(Long opportunityId) {
        return promptMapper.selectList(new LambdaQueryWrapper<OpportunityFollowupPrompt>()
                .eq(OpportunityFollowupPrompt::getOpportunityId, opportunityId)
                .orderByDesc(OpportunityFollowupPrompt::getSentAt)
                .orderByDesc(OpportunityFollowupPrompt::getId));
    }

    /**
     * 我的商机：负责人是当前用户，且未收口；可按销售阶段与「超过 silentDays 天没跟进」过滤。
     *
     * <p>静默口径含「从未跟进」的商机（last_follow_up_at 为空），它们才是最该催的。
     *
     * @param stage      销售阶段，空则不限
     * @param silentDays 静默天数，null 则不看静默
     * @param limit      返回条数上限
     */
    public List<SalesOpportunity> mine(Long userId, String stage, Integer silentDays, int limit) {
        if (userId == null) {
            return List.of();
        }
        LambdaQueryWrapper<SalesOpportunity> query = new LambdaQueryWrapper<SalesOpportunity>()
                .eq(SalesOpportunity::getOwnerUserId, userId)
                .notIn(SalesOpportunity::getStatus, CLOSED_STATUSES);
        if (StringUtils.hasText(stage)) {
            query.eq(SalesOpportunity::getStatus, stage.strip());
        }
        if (silentDays != null) {
            LocalDateTime before = LocalDateTime.now().minusDays(Math.max(1, silentDays));
            query.and(w -> w.isNull(SalesOpportunity::getLastFollowUpAt)
                    .or().lt(SalesOpportunity::getLastFollowUpAt, before));
        }
        query.orderByAsc(SalesOpportunity::getNextFollowUpAt).orderByDesc(SalesOpportunity::getId);
        return opportunityMapper.selectList(query.last("LIMIT " + Math.max(1, limit)));
    }

    /** 商机按归属人过滤（AI 工具的「我的」口径）。 */
    public boolean ownedBy(SalesOpportunity opportunity, Long userId) {
        return opportunity != null && userId != null && userId.equals(opportunity.getOwnerUserId());
    }

    // ==================== 回填 ====================

    /** 回填结果：扫到多少行、填了多少行、解析不出跳过多少行。 */
    public record BackfillResult(int scanned, int filled, int skipped) {
    }

    /**
     * 一次性回填 {@code next_follow_up_at}：把历史遗留的 {@code next_follow_up} 文本解析成结构化时间。
     *
     * <p>复用 {@link OpportunityFollowUpParser#parseNextFollowUp}，不另造一套解析；解析不出来就保留 NULL
     * （宁可「今日应跟」漏一条，也不猜一个错时间把商机推给别人），由人工补录。
     *
     * <p>幂等：只扫 {@code next_follow_up <> '' AND next_follow_up_at IS NULL} 的行，已填过的一律不动，
     * 重复调用安全。
     *
     * @return 扫描/填充/跳过的行数
     */
    public BackfillResult backfillNextFollowUpAt() {
        LocalDate today = LocalDate.now();
        List<SalesOpportunity> rows = opportunityMapper.selectList(new LambdaQueryWrapper<SalesOpportunity>()
                .ne(SalesOpportunity::getNextFollowUp, "")
                .isNull(SalesOpportunity::getNextFollowUpAt));
        int scanned = 0;
        int filled = 0;
        int skipped = 0;
        for (SalesOpportunity row : rows) {
            scanned++;
            // 双保险：查询已排除已填行，这里再挡一次，避免并发回填互相覆盖
            if (row.getNextFollowUpAt() != null) {
                skipped++;
                continue;
            }
            LocalDateTime nextAt = OpportunityFollowUpParser.parseNextFollowUp(row.getNextFollowUp(), today);
            if (nextAt == null) {
                skipped++;
                continue;
            }
            row.setNextFollowUpAt(nextAt);
            opportunityMapper.updateById(row);
            filled++;
        }
        log.info("下次跟进时间回填完成: scanned={}, filled={}, skipped={}", scanned, filled, skipped);
        return new BackfillResult(scanned, filled, skipped);
    }

    // ==================== 问句 ====================

    /**
     * 生成问句正文：最多 4 行，只带商机名（截 12 字）、阶段和时间，<b>绝不带金额与毛利</b>。
     */
    public String buildPrompt(SalesOpportunity opportunity, String stage) {
        String name = shorten(opportunity == null ? null : opportunity.getName(), MAX_NAME);
        String stageName = StringUtils.hasText(stage) ? stage : "跟进";
        String timing = describeTiming(opportunity);
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(stageName).append("】商机「").append(name).append("」")
                .append(timing).append("，现在什么进展？");
        sb.append('\n').append("客户那边的态度和卡点是什么？");
        sb.append('\n').append("下一步动作与时间（回我一句，例如「下周三 15:00 再沟通」）");
        sb.append('\n').append("成交概率有变化吗？（0-100）");
        return sb.toString();
    }

    /**
     * 落一条问句（幂等）：同一商机同阶段每天只落一次；撞唯一键直接返回已有行。
     *
     * <p>目标人默认取商机负责人；试点期由调度器显式传入实际接收人。
     */
    public OpportunityFollowupPrompt createPrompt(Long oppId, String stage, String channel) {
        return createPrompt(oppId, stage, channel, null);
    }

    /**
     * 落一条问句（幂等），并显式指定台账的目标接收人。
     *
     * <p>试点期问句一律发给机器人创建人，配额也按该接收人计数，所以 {@code targetUserId} 由调用方指定；
     * 传 null 时沿用商机负责人（正式期口径）。
     */
    public OpportunityFollowupPrompt createPrompt(Long oppId, String stage, String channel,
            Long targetUserId) {
        SalesOpportunity opportunity = get(oppId);
        String stageKey = StringUtils.hasText(stage) ? stage : "跟进";
        LocalDate today = LocalDate.now();
        String dedupeKey = dedupeKey(oppId, stageKey, today);
        OpportunityFollowupPrompt existing = findByDedupe(dedupeKey);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        OpportunityFollowupPrompt row = new OpportunityFollowupPrompt();
        row.setOpportunityId(oppId);
        row.setTargetUserId(targetUserId != null ? targetUserId : opportunity.getOwnerUserId());
        row.setRemindStage(shorten(stageKey, 16));
        row.setChannel(StringUtils.hasText(channel) ? channel : CHANNEL_INAPP);
        row.setQuestionText(shorten(buildPrompt(opportunity, stageKey), 500));
        row.setSentAt(now);
        row.setState(OpportunityFollowupPrompt.STATE_SENT);
        row.setDedupeKey(dedupeKey);
        row.setCreatedAt(now);
        try {
            promptMapper.insert(row);
            return row;
        } catch (DuplicateKeyException e) {
            // 并发扫描撞唯一键：返回已存在的那条，不重复问
            OpportunityFollowupPrompt concurrent = findByDedupe(dedupeKey);
            if (concurrent != null) {
                return concurrent;
            }
            throw e;
        }
    }

    /** 幂等键：{@code opp:商机ID:阶段:yyyy-MM-dd}。 */
    public static String dedupeKey(Long oppId, String stage, LocalDate day) {
        LocalDate date = day == null ? LocalDate.now() : day;
        return "opp:" + oppId + ":" + (StringUtils.hasText(stage) ? stage : "跟进") + ":"
                + DEDUPE_DATE.format(date);
    }

    private OpportunityFollowupPrompt findByDedupe(String dedupeKey) {
        return promptMapper.selectOne(new LambdaQueryWrapper<OpportunityFollowupPrompt>()
                .eq(OpportunityFollowupPrompt::getDedupeKey, dedupeKey)
                .last("limit 1"));
    }

    // ==================== 回复 ====================

    /**
     * 处理一条企微回复：只认 24 小时内最新一条 {@code SENT} 问句，处理一次；重复投递不重复写。
     *
     * @param senderKey     企微 userid（用于核对归属，可空）
     * @param opportunityId 商机 ID
     * @param text          回复正文
     * @param msgId         消息 ID，用于幂等与排查
     * @param channel       入站渠道（WECOM_APP / INAPP）
     * @return 被更新的问句；没有可处理问句时返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public OpportunityFollowupPrompt applyReply(String senderKey, Long opportunityId, String text,
                                                String msgId, String channel) {
        return applyReply(senderKey, opportunityId, text, msgId, null, channel);
    }

    /** 同上，额外带上企微会话 ID（单聊场景可空）。 */
    @Transactional(rollbackFor = Exception.class)
    public OpportunityFollowupPrompt applyReply(String senderKey, Long opportunityId, String text,
                                                String msgId, String chatId, String channel) {
        if (opportunityId == null || !StringUtils.hasText(text)) {
            return null;
        }
        OpportunityFollowupPrompt prompt = findLatestSent(opportunityId);
        if (prompt == null) {
            log.info("商机回复没有可处理的问句，忽略: oppId={} msgId={} channel={}", opportunityId, msgId, channel);
            return null;
        }
        return applyReplyTo(prompt, senderKey, text, msgId, chatId);
    }

    /**
     * 按发送人反查最近未回复问句并写回台账（企微智能机器人/转发器回调走这里）。
     *
     * <p>不区分引用（旧行为）：直接取 24 小时内最新一条 SENT 问句。
     *
     * @return 被更新的问句；没匹配到问句或 senderKey/text 为空时返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public OpportunityFollowupPrompt applyReplyBySender(String senderKey, String text, String msgId,
                                                        String chatId, String channel) {
        return applyReplyBySender(senderKey, text, msgId, chatId, null, channel);
    }

    /**
     * 按发送人反查未回复问句并写回台账，<b>优先用引用内容锚定</b>（企微智能机器人/转发器回调走这里）。
     *
     * <p>为什么不能只按「发送人 + 24h 内最新 SENT」取：同一个人同一天可能收到多条问句
     * （例如调度器 09:20 一次给 3 个商机各发一条，sent_at 相差不到 2 秒），此时「最新一条」
     * 与用户实际引用回复的那条没有必然关系，会把 A 商机的回复写到 B 商机台账上。
     * <b>引用内容才是唯一可靠的锚点</b>：企微回调帧带 {@code body.quote.text.content}，
     * 它是被引用问句的完整原文，据此可以精确还原用户到底在回哪一条。
     *
     * <p>匹配顺序：
     * <ol>
     *   <li>候选集：{@code wecom_userid = senderKey AND sent_at 在 7 天内}，<b>不限 state</b>，
     *       按 {@code sent_at desc, id desc} 取最多 20 条；</li>
     *   <li>引用命中：候选问句正文第一行（去空白）出现在引用原文里 → 选中该条（多条命中取最新）；</li>
     *   <li>商机名兜底：引用原文里 {@code 「」} 之间 4 字以上的片段，与候选 questionText 比对；</li>
     *   <li>命中 {@code SENT} 问句 → 直接写回该台账；命中 <b>已回复/已确认</b> 的问句（PARSED/REPLIED/
     *       CONFIRMED）→ <b>克隆一条新台账</b>再写回，绝不覆盖历史；</li>
     *   <li>引用未命中（或没带引用）：退回 24 小时内最新 {@code SENT}（旧行为）；连 SENT 都没有就返回
     *       null，不凭猜造台账，由 sidecar 提示找人工。</li>
     * </ol>
     *
     * <p>{@code msgId} 落在 {@code inbound_msg_id}（唯一键）上做幂等，重复投递直接返回已有行、不覆盖。
     *
     * @param quotedText 被引用消息的原文（企微 {@code body.quote.text.content}），可为空
     * @return 被更新的问句；没匹配到问句或 senderKey/text 为空时返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public OpportunityFollowupPrompt applyReplyBySender(String senderKey, String text, String msgId,
                                                        String chatId, String quotedText, String channel) {
        String key = StringUtils.hasText(senderKey) ? senderKey.strip() : null;
        String reply = StringUtils.hasText(text) ? text.strip() : null;
        String messageId = StringUtils.hasText(msgId) ? msgId.strip() : null;
        String sessionId = StringUtils.hasText(chatId) ? chatId.strip() : null;
        if (key == null || reply == null) {
            return null;
        }
        if (messageId != null) {
            OpportunityFollowupPrompt delivered = findByInboundMsgId(messageId);
            if (delivered != null) {
                // 企微回调会重试：同一 msgid 再来一次直接返回已有行，不覆盖已解析结果
                log.info("企微入站回复重复投递，返回已有台账: senderKey={} msgId={} promptId={}",
                        key, messageId, delivered.getId());
                return delivered;
            }
        }
        // 候选集放宽到 7 天且不限 state：已经回复过（PARSED/REPLIED/CONFIRMED）的问句同样可能被再次引用回复，
        // 只取 SENT 会漏掉「同一个商机多次追进展」这个真实场景。
        LocalDateTime since = LocalDateTime.now().minusDays(REPLY_QUOTED_WINDOW_DAYS);
        List<OpportunityFollowupPrompt> candidates = promptMapper.selectList(
                new LambdaQueryWrapper<OpportunityFollowupPrompt>()
                        .eq(OpportunityFollowupPrompt::getWecomUserid, key)
                        .ge(OpportunityFollowupPrompt::getSentAt, since)
                        .orderByDesc(OpportunityFollowupPrompt::getSentAt)
                        .orderByDesc(OpportunityFollowupPrompt::getId)
                        .last("limit " + REPLY_CANDIDATE_LIMIT));
        if (candidates == null || candidates.isEmpty()) {
            log.info("企微入站回复没有可处理的问句，忽略: senderKey={} msgId={} channel={}",
                    key, messageId, channel);
            return null;
        }
        OpportunityFollowupPrompt matched = pickByQuotedText(candidates, quotedText);
        if (matched != null) {
            if (OpportunityFollowupPrompt.STATE_SENT.equals(matched.getState())) {
                return applyReplyTo(matched, key, reply, messageId, sessionId);
            }
            // 被引用的问句已经回复过：不覆盖历史台账（它带着上次的 reply_text / parsed_json / 跟进记录 ID），
            // 克隆一条新台账承载这次回复，自动落库后就是一条新的跟进记录。
            OpportunityFollowupPrompt clone = clonePromptForReply(matched, channel);
            log.info("企微回复命中的问句已回复过，克隆新台账: srcPromptId={} newPromptId={} oppId={}",
                    matched.getId(), clone.getId(), matched.getOpportunityId());
            return applyReplyTo(clone, key, reply, messageId, sessionId);
        }
        // 旧行为兜底：24 小时内最新 SENT（带引用却没命中说明问句模板或引用结构变了，留日志便于排查）。
        OpportunityFollowupPrompt latestSent = firstSentWithinWindow(candidates);
        if (latestSent == null) {
            // 既没命中引用、又没有待回复的 SENT：宁可返回 null（sidecar 提示找人工），也不乱造台账。
            log.info("企微入站回复既未命中引用也无 24 小时内 SENT 问句，忽略: senderKey={} msgId={} 候选数={} 有引用={}",
                    key, messageId, candidates.size(), StringUtils.hasText(quotedText));
            return null;
        }
        log.info("企微入站回复的引用未匹配到问句，回退最新 SENT: senderKey={} msgId={} 候选数={} promptId={} 有引用={}",
                key, messageId, candidates.size(), latestSent.getId(), StringUtils.hasText(quotedText));
        return applyReplyTo(latestSent, key, reply, messageId, sessionId);
    }

    /** 候选里 24 小时内最新一条 {@code SENT}（旧行为兜底）；候选取不到 SENT 时返回 null。 */
    private static OpportunityFollowupPrompt firstSentWithinWindow(
            List<OpportunityFollowupPrompt> candidates) {
        LocalDateTime since = LocalDateTime.now().minusHours(REPLY_WINDOW_HOURS);
        for (OpportunityFollowupPrompt candidate : candidates) {
            if (!OpportunityFollowupPrompt.STATE_SENT.equals(candidate.getState())) {
                continue;
            }
            if (candidate.getSentAt() == null || candidate.getSentAt().isBefore(since)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * 为「对已回复问句的再次回复」克隆一条新台账。
     *
     * <p>为什么克隆而不是复用原台账：原台账已经带着上一次的 {@code reply_text} / {@code parsed_json} /
     * 跟进记录 ID，覆盖它等于抹掉历史跟进，溯源与审计都没了。
     *
     * <p>{@code question_text} 是 NOT NULL：原问句为空时用商机名拼一句；
     * {@code dedupe_key} 带毫秒时间戳，避开 {@code opp:商机ID:阶段:日期} 的唯一键。
     */
    private OpportunityFollowupPrompt clonePromptForReply(OpportunityFollowupPrompt source, String channel) {
        LocalDateTime now = LocalDateTime.now();
        String questionText = shorten(source.getQuestionText(), 500);
        if (!StringUtils.hasText(questionText)) {
            questionText = shorten(fallbackPromptText(source.getOpportunityId()), 500);
        }
        OpportunityFollowupPrompt row = new OpportunityFollowupPrompt();
        row.setOpportunityId(source.getOpportunityId());
        row.setTargetUserId(source.getTargetUserId());
        row.setWecomUserid(source.getWecomUserid());
        row.setRemindStage(shorten(STAGE_FOLLOWUP_REPLY, 16));
        row.setChannel(StringUtils.hasText(channel) ? channel : CHANNEL_INAPP);
        row.setQuestionText(questionText);
        row.setSentAt(now);
        row.setState(OpportunityFollowupPrompt.STATE_SENT);
        row.setCreatedAt(now);
        row.setDedupeKey("opp:" + source.getOpportunityId() + ":" + STAGE_FOLLOWUP_REPLY + ":"
                + DEDUPE_DATE.format(LocalDate.now()) + ":" + System.currentTimeMillis());
        promptMapper.insert(row);
        return row;
    }

    /** 克隆台账的兜底正文（原问句正文为空时用，保证 NOT NULL 列不落空）。 */
    private String fallbackPromptText(Long opportunityId) {
        String name = null;
        try {
            SalesOpportunity opportunity = opportunityId == null ? null : opportunityMapper.selectById(opportunityId);
            name = opportunity == null ? null : shorten(opportunity.getName(), MAX_NAME);
        } catch (Exception e) {
            log.warn("克隆问句回查商机名失败，用兜底文案: opportunityId={} err={}", opportunityId, e.getMessage());
        }
        return "【" + STAGE_FOLLOWUP_REPLY + "】商机「" + (StringUtils.hasText(name) ? name : "未知")
                + "」，现在什么进展？";
    }

    /**
     * 用引用原文在候选问句里挑出被回复的那条；挑不到返回 null。
     *
     * <p>候选已按 {@code sent_at desc} 排好序，顺序扫描即「命中多条时取最新」。
     */
    private OpportunityFollowupPrompt pickByQuotedText(List<OpportunityFollowupPrompt> candidates,
                                                      String quotedText) {
        String quoted = normalizeForMatch(quotedText);
        if (quoted.isEmpty()) {
            return null;
        }
        // 1) 引用命中：候选问句正文第一行出现在引用原文里
        for (OpportunityFollowupPrompt candidate : candidates) {
            String firstLine = normalizeForMatch(firstLineOf(candidate.getQuestionText()));
            if (!firstLine.isEmpty() && quoted.contains(firstLine)) {
                return candidate;
            }
        }
        // 2) 商机名兜底：「」之间 4 字以上的片段，候选正文里也有同一片段
        for (String fragment : bracketFragments(quotedText)) {
            String normalized = normalizeForMatch(fragment);
            if (normalized.isEmpty()) {
                continue;
            }
            for (OpportunityFollowupPrompt candidate : candidates) {
                if (normalizeForMatch(candidate.getQuestionText()).contains(normalized)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** 问句正文第一行（问句可能多行，首行才是引用时可稳定比对的部分）。 */
    private static String firstLineOf(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        int breakAt = text.indexOf('\n');
        String firstLine = breakAt < 0 ? text : text.substring(0, breakAt);
        return firstLine.strip();
    }

    /** 比对用归一化：去首尾空白并把连续空白压成单空格（引用里会带缩进/换行）。 */
    private static String normalizeForMatch(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.strip().replaceAll("\\s+", " ");
    }

    /** 提取文本里 {@code 「…」} 中 4 字以上的片段（商机名），按出现顺序返回。 */
    private static List<String> bracketFragments(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> fragments = new ArrayList<>();
        for (String opening : new String[] {"「", "『"}) {
            String closing = "「".equals(opening) ? "」" : "』";
            int from = 0;
            while (true) {
                int start = text.indexOf(opening, from);
                if (start < 0) {
                    break;
                }
                int end = text.indexOf(closing, start + opening.length());
                if (end < 0) {
                    break;
                }
                String fragment = text.substring(start + opening.length(), end).strip();
                if (fragment.length() >= 4) {
                    fragments.add(fragment);
                }
                from = end + closing.length();
            }
        }
        return fragments;
    }

    /**
     * 站内手工粘贴企微回复（企微转发失败/漏推时的兜底入口）。
     *
     * <p>先按 {@code opportunityId} 找 24 小时内最新 {@code SENT} 问句；没有就补一条「站内补录」台账
     * （dedupe_key 带秒级时间戳，保证不撞唯一键），再走同一套解析写回。
     */
    @Transactional(rollbackFor = Exception.class)
    public OpportunityFollowupPrompt pasteReply(Long opportunityId, String text) {
        if (!StringUtils.hasText(text)) {
            throw new RuntimeException("回复内容不能为空");
        }
        SalesOpportunity opportunity = get(opportunityId);
        OpportunityFollowupPrompt prompt = findLatestSent(opportunity.getId());
        if (prompt == null) {
            LocalDateTime now = LocalDateTime.now();
            OpportunityFollowupPrompt row = new OpportunityFollowupPrompt();
            row.setOpportunityId(opportunity.getId());
            row.setTargetUserId(opportunity.getOwnerUserId());
            row.setRemindStage("站内补录");
            row.setChannel(CHANNEL_INAPP);
            row.setQuestionText("站内补录：请补充客户回复");
            row.setSentAt(now);
            row.setState(OpportunityFollowupPrompt.STATE_SENT);
            // 补录行没有「阶段 + 日期」语义的天然幂等键，用秒级时间戳保证每次手贴都是独立一行
            row.setDedupeKey("opp:" + opportunity.getId() + ":站内补录:" + DEDUPE_DATE.format(LocalDate.now())
                    + ":" + (System.currentTimeMillis() / 1000));
            row.setCreatedAt(now);
            promptMapper.insert(row);
            prompt = row;
        }
        return applyReplyTo(prompt, null, text, null, null);
    }

    /** 忽略一条问句：不再等回复，台账置 EXPIRED（repliedAt 补 now 便于看「什么时候被放弃的」）。 */
    @Transactional(rollbackFor = Exception.class)
    public void ignorePrompt(Long promptId) {
        OpportunityFollowupPrompt prompt = promptId == null ? null : promptMapper.selectById(promptId);
        if (prompt == null) {
            throw new RuntimeException("问句不存在");
        }
        prompt.setState(OpportunityFollowupPrompt.STATE_EXPIRED);
        if (prompt.getRepliedAt() == null) {
            prompt.setRepliedAt(LocalDateTime.now());
        }
        promptMapper.updateById(prompt);
    }

    /**
     * 过期巡检：超过 24 小时仍是 {@code SENT} 的问句批量置 EXPIRED。
     *
     * <p>先查再逐条 update（量小），不走批量 SQL，保持与其它台账写法一致。
     *
     * @return 置为 EXPIRED 的条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int expireStale() {
        LocalDateTime since = LocalDateTime.now().minusHours(REPLY_WINDOW_HOURS);
        List<OpportunityFollowupPrompt> rows = promptMapper.selectList(
                new LambdaQueryWrapper<OpportunityFollowupPrompt>()
                        .eq(OpportunityFollowupPrompt::getState, OpportunityFollowupPrompt.STATE_SENT)
                        .lt(OpportunityFollowupPrompt::getSentAt, since));
        int expired = 0;
        for (OpportunityFollowupPrompt row : rows == null ? List.<OpportunityFollowupPrompt>of() : rows) {
            row.setState(OpportunityFollowupPrompt.STATE_EXPIRED);
            promptMapper.updateById(row);
            expired++;
        }
        return expired;
    }

    /** 商机 24 小时内最新一条待回复问句（找不到返回 null）。 */
    private OpportunityFollowupPrompt findLatestSent(Long opportunityId) {
        LocalDateTime since = LocalDateTime.now().minusHours(REPLY_WINDOW_HOURS);
        return promptMapper.selectOne(new LambdaQueryWrapper<OpportunityFollowupPrompt>()
                .eq(OpportunityFollowupPrompt::getOpportunityId, opportunityId)
                .eq(OpportunityFollowupPrompt::getState, OpportunityFollowupPrompt.STATE_SENT)
                .ge(OpportunityFollowupPrompt::getSentAt, since)
                .orderByDesc(OpportunityFollowupPrompt::getSentAt)
                .orderByDesc(OpportunityFollowupPrompt::getId)
                .last("limit 1"));
    }

    /** 按企微入站消息 ID 找台账（幂等键）。 */
    private OpportunityFollowupPrompt findByInboundMsgId(String msgId) {
        return promptMapper.selectOne(new LambdaQueryWrapper<OpportunityFollowupPrompt>()
                .eq(OpportunityFollowupPrompt::getInboundMsgId, msgId)
                .last("limit 1"));
    }

    /**
     * 把一条回复写进指定问句台账：reply_text + 解析结果 + state，并记下入站 msgid / 会话 ID。
     *
     * <p>企微按发送人反查与站内补录都走这里，保证两条入口的解析与状态口径完全一致。
     *
     * <p><b>解析出成交概率或下次跟进时间就直接落库</b>（写跟进记录 + 刷新商机，台账直置 CONFIRMED），
     * 开关 {@code opportunity.followup.auto-confirm} 默认开；没解析到结构化字段时只记原文，台账保持 PARSED/REPLIED 等人工补。
     */
    private OpportunityFollowupPrompt applyReplyTo(OpportunityFollowupPrompt prompt, String senderKey,
                                                   String text, String msgId, String chatId) {
        if (prompt == null || prompt.getId() == null || !StringUtils.hasText(text)) {
            return null;
        }
        String reply = shorten(text.strip(), 2000);
        OpportunityFollowUpParser.ReplyDraft draft = OpportunityFollowUpParser.parseReply(reply);
        // 自动落库：只要解析出成交概率或下次跟进时间就直接写库，不再等人确认（开关见 AUTO_CONFIRM_KEY）
        boolean autoConfirmable = draft != null
                && (draft.probability() != null || draft.nextAt() != null)
                && autoConfirmEnabled();
        prompt.setRepliedAt(LocalDateTime.now());
        prompt.setReplyText(reply);
        if (autoConfirmable) {
            prompt.setState(OpportunityFollowupPrompt.STATE_CONFIRMED);
            prompt.setParsedJson(toJson(draft));
        } else if (draft != null
                && (draft.stage() != null || draft.probability() != null || draft.nextAt() != null)) {
            prompt.setState(OpportunityFollowupPrompt.STATE_PARSED);
            prompt.setParsedJson(toJson(draft));
        } else {
            prompt.setState(OpportunityFollowupPrompt.STATE_REPLIED);
            prompt.setParsedJson(draft == null ? null : toJson(draft));
        }
        if (StringUtils.hasText(senderKey) && !StringUtils.hasText(prompt.getWecomUserid())) {
            prompt.setWecomUserid(shorten(senderKey, 64));
        }
        if (StringUtils.hasText(msgId)) {
            prompt.setInboundMsgId(shorten(msgId, 64));
        }
        if (StringUtils.hasText(chatId)) {
            prompt.setChatId(shorten(chatId, 64));
        }
        promptMapper.updateById(prompt);
        if (autoConfirmable) {
            autoConfirm(prompt, draft);
        }
        // 回复已落库之后才入队教练：教练生成是「锦上添花」，任何异常都必须止步于日志
        try {
            coachService.enqueue(prompt.getOpportunityId(), prompt.getId(), "INBOUND", prompt.getTargetUserId());
        } catch (Exception e) {
            log.warn("商机 AI 教练生成入队失败（不影响回复落库）: promptId={} err={}", prompt.getId(), e.getMessage());
        }
        return prompt;
    }

    // ==================== 确认落库 ====================

    /**
     * 人工（或本人）确认一条问句的解析结果：写跟进记录 + 更新商机快照，问句转 CONFIRMED。
     *
     * <p>阶段推进必须满足报价单前置条件，否则抛业务异常，不静默失败。
     */
    @Transactional(rollbackFor = Exception.class)
    public SalesOpportunityFollowUp confirmDraft(Long promptId, Long operatorUserId) {
        OpportunityFollowupPrompt prompt = promptId == null ? null : promptMapper.selectById(promptId);
        if (prompt == null) {
            throw new RuntimeException("问句不存在");
        }
        if (OpportunityFollowupPrompt.STATE_CONFIRMED.equals(prompt.getState())) {
            SalesOpportunityFollowUp existing = followUpMapper.selectOne(
                    new LambdaQueryWrapper<SalesOpportunityFollowUp>()
                            .eq(SalesOpportunityFollowUp::getPromptId, prompt.getId())
                            .last("limit 1"));
            if (existing != null) {
                return existing;
            }
        }
        if (!StringUtils.hasText(prompt.getReplyText())) {
            throw new RuntimeException("该问句还没有回复内容，无法确认");
        }
        SalesOpportunity opportunity = get(prompt.getOpportunityId());
        OpportunityFollowUpParser.ReplyDraft draft =
                OpportunityFollowUpParser.parseReply(prompt.getReplyText());
        String targetStatus = draft != null && StringUtils.hasText(draft.stage())
                ? draft.stage() : opportunity.getStatus();
        ensureStageAllowed(opportunity, targetStatus);

        LocalDateTime now = LocalDateTime.now();
        Integer probability = draft != null && draft.probability() != null
                ? draft.probability() : opportunity.getProbability();
        LocalDateTime nextAt = draft == null ? null : draft.nextAt();
        String nextText = nextAt == null ? null : NEXT_TEXT.format(nextAt);

        SalesOpportunityFollowUp item = new SalesOpportunityFollowUp();
        item.setOpportunityId(opportunity.getId());
        item.setFollowUpAt(now);
        item.setFollower(followerName(operatorUserId, prompt.getTargetUserId()));
        item.setContent(shorten(prompt.getReplyText(), 1000));
        item.setStatus(shorten(targetStatus, 30));
        item.setProbability(probability == null ? 0 : probability);
        item.setNextFollowUp(shorten(nextText, 100));
        item.setSource(SOURCE_WECOM_BOT);
        item.setPromptId(prompt.getId());
        item.setCreatedAt(now);
        followUpMapper.insert(item);

        opportunity.setStatus(targetStatus);
        opportunity.setProbability(probability);
        opportunity.setNextFollowUpAt(nextAt);
        opportunity.setNextFollowUp(nextText);
        opportunity.setLastFollowUpAt(now);
        opportunity.setFollowUpState(nextAt == null ? "DONE" : "PENDING");
        opportunityMapper.updateById(opportunity);

        prompt.setState(OpportunityFollowupPrompt.STATE_CONFIRMED);
        if (prompt.getRepliedAt() == null) {
            prompt.setRepliedAt(now);
        }
        promptMapper.updateById(prompt);
        return item;
    }

    /**
     * 自动落库：企微回复解析出成交概率/下次跟进时间后，直接写跟进记录并刷新商机快照。
     *
     * <p>与 {@link #confirmDraft} 的唯一差别是<b>绝不改阶段</b>：企微里随口提到阶段也不自动推进，
     * 保持「阶段由人工口径决定」，因此这里不调 {@code ensureStageAllowed}，也不会被报价单前置条件卡住。
     *
     * @return 新写入的跟进记录
     */
    private SalesOpportunityFollowUp autoConfirm(OpportunityFollowupPrompt prompt,
                                                 OpportunityFollowUpParser.ReplyDraft draft) {
        SalesOpportunity opportunity = get(prompt.getOpportunityId());
        LocalDateTime now = LocalDateTime.now();
        Integer probability = draft != null && draft.probability() != null
                ? draft.probability() : opportunity.getProbability();
        LocalDateTime nextAt = draft == null ? null : draft.nextAt();
        String nextText = nextAt == null ? null : NEXT_TEXT.format(nextAt);
        // 阶段保持商机原值：自动落库只刷新概率与跟进时间
        String status = opportunity.getStatus();

        SalesOpportunityFollowUp item = new SalesOpportunityFollowUp();
        item.setOpportunityId(opportunity.getId());
        item.setFollowUpAt(now);
        item.setFollower(replierName(prompt));
        item.setContent(shorten(prompt.getReplyText(), 1000));
        item.setStatus(shorten(status, 30));
        item.setProbability(probability == null ? 0 : probability);
        item.setNextFollowUp(shorten(nextText, 100));
        item.setSource(SOURCE_WECOM_BOT);
        item.setPromptId(prompt.getId());
        item.setCreatedAt(now);
        followUpMapper.insert(item);

        opportunity.setProbability(probability);
        opportunity.setLastFollowUpAt(now);
        if (nextAt != null) {
            opportunity.setNextFollowUpAt(nextAt);
            opportunity.setNextFollowUp(nextText);
            opportunity.setFollowUpState("PENDING");
        }
        opportunityMapper.updateById(opportunity);

        prompt.setState(OpportunityFollowupPrompt.STATE_CONFIRMED);
        if (prompt.getRepliedAt() == null) {
            prompt.setRepliedAt(now);
        }
        promptMapper.updateById(prompt);
        log.info("企微回复自动落库: opportunityId={} promptId={} probability={} nextAt={}",
                opportunity.getId(), prompt.getId(), probability, nextText);
        return item;
    }

    /**
     * 自动落库开关：读系统配置 {@code opportunity.followup.auto-confirm}，<b>读不到/异常都按开启</b>。
     */
    private boolean autoConfirmEnabled() {
        try {
            String value = systemConfigService.getValue(CONFIG_GROUP, AUTO_CONFIRM_KEY, "true");
            return !StringUtils.hasText(value) || !"false".equalsIgnoreCase(value.strip());
        } catch (Exception e) {
            log.warn("读取自动落库开关失败，按开启处理: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 跟进人 = 「回复机器人的那个人」：
     * 企微 userid 映射 → 问句目标用户 → 商机负责人文本 → 兜底文案；任一步取不到就降级不抛错。
     */
    private String replierName(OpportunityFollowupPrompt prompt) {
        if (prompt == null) {
            return "企微回复";
        }
        if (StringUtils.hasText(prompt.getWecomUserid())) {
            String mapped = nameByWecomUserid(prompt.getWecomUserid().strip());
            if (StringUtils.hasText(mapped)) {
                return shorten(mapped, 100);
            }
        }
        String target = userName(prompt.getTargetUserId());
        if (StringUtils.hasText(target)) {
            return shorten(target, 100);
        }
        try {
            SalesOpportunity opportunity = prompt.getOpportunityId() == null
                    ? null : opportunityMapper.selectById(prompt.getOpportunityId());
            if (opportunity != null && StringUtils.hasText(opportunity.getOwner())) {
                return shorten(opportunity.getOwner(), 100);
            }
        } catch (Exception e) {
            log.warn("回复人回退商机负责人失败: opportunityId={} err={}", prompt.getOpportunityId(), e.getMessage());
        }
        return "企微回复";
    }

    /** 企微 userid → 绑定账号实名（enabled=1 才认）。 */
    private String nameByWecomUserid(String wecomUserid) {
        if (!StringUtils.hasText(wecomUserid)) {
            return null;
        }
        try {
            List<EmailWeComMapping> mappings = weComMappingMapper.selectList(
                    new LambdaQueryWrapper<EmailWeComMapping>()
                            .eq(EmailWeComMapping::getWecomUserId, wecomUserid)
                            .eq(EmailWeComMapping::getEnabled, 1)
                            .last("limit 1"));
            if (mappings == null || mappings.isEmpty() || mappings.get(0) == null) {
                return null;
            }
            return userName(mappings.get(0).getOwnerUserId());
        } catch (Exception e) {
            log.warn("回复人企微映射查询失败: wecomUserid={} err={}", wecomUserid, e.getMessage());
            return null;
        }
    }

    /** 用户显示名：实名优先，没有就用户名；查不到/异常返回 null。 */
    private String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            User user = userMapper.selectById(userId);
            if (user == null) {
                return null;
            }
            return StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername();
        } catch (Exception e) {
            log.warn("回复人查询失败: userId={} err={}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 手工（或 AI 工具）补一条跟进记录：写跟进明细 + 刷新商机快照。
     *
     * <p>只有真的改阶段时才走阶段守卫；阶段没变就是纯记录，不会被报价前置条件卡住。
     *
     * @param stage        目标阶段，为空则沿用商机当前阶段
     * @param probability  成交概率 0-100，为空则沿用
     * @param content      跟进内容
     * @param nextAt       下次跟进时间，为空表示本轮跟进闭环
     */
    @Transactional(rollbackFor = Exception.class)
    public SalesOpportunityFollowUp recordManualFollowUp(Long opportunityId, Long operatorUserId,
                                                         String stage, Integer probability,
                                                         String content, LocalDateTime nextAt) {
        SalesOpportunity opportunity = get(opportunityId);
        String targetStatus = StringUtils.hasText(stage) ? stage.strip() : opportunity.getStatus();
        if (StringUtils.hasText(stage) && !targetStatus.equals(opportunity.getStatus())) {
            ensureStageAllowed(opportunity, targetStatus);
        }
        LocalDateTime now = LocalDateTime.now();
        Integer targetProbability = probability == null ? opportunity.getProbability() : probability;
        String nextText = nextAt == null ? null : NEXT_TEXT.format(nextAt);

        SalesOpportunityFollowUp item = new SalesOpportunityFollowUp();
        item.setOpportunityId(opportunity.getId());
        item.setFollowUpAt(now);
        item.setFollower(followerName(operatorUserId, opportunity.getOwnerUserId()));
        item.setContent(shorten(StringUtils.hasText(content) ? content.strip() : "已跟进", 1000));
        item.setStatus(shorten(targetStatus, 30));
        item.setProbability(targetProbability == null ? 0 : targetProbability);
        item.setNextFollowUp(shorten(nextText, 100));
        item.setSource(SOURCE_MANUAL);
        item.setCreatedAt(now);
        followUpMapper.insert(item);

        opportunity.setStatus(targetStatus);
        opportunity.setProbability(targetProbability);
        opportunity.setLastFollowUpAt(now);
        if (nextAt != null) {
            opportunity.setNextFollowUpAt(nextAt);
            opportunity.setNextFollowUp(nextText);
            opportunity.setFollowUpState("PENDING");
        } else if (opportunity.getNextFollowUpAt() != null) {
            opportunity.setFollowUpState("DONE");
        }
        opportunityMapper.updateById(opportunity);
        return item;
    }

    /** 静音/恢复提醒。 */
    public void mute(Long oppId, boolean muted) {
        SalesOpportunity opportunity = get(oppId);
        opportunity.setRemindMuted(muted ? 1 : 0);
        opportunityMapper.updateById(opportunity);
    }

    // ==================== 企微下发 ====================

    /**
     * 尝试把问句推到负责人企微；任何异常都吞掉（不影响落库与其它规则）。
     *
     * @return 实际渠道：推送成功返回 WECOM_APP，否则 INAPP
     */
    public String tryPush(OpportunityFollowupPrompt prompt, Long ownerUserId) {
        if (prompt == null || ownerUserId == null) {
            return CHANNEL_INAPP;
        }
        try {
            String wecomUserId = wecomUserIdOf(ownerUserId);
            if (!StringUtils.hasText(wecomUserId)) {
                return CHANNEL_INAPP;
            }
            weComClient.pushText(wecomUserId, prompt.getQuestionText());
            prompt.setChannel(CHANNEL_WECOM_APP);
            prompt.setWecomUserid(shorten(wecomUserId, 64));
            promptMapper.updateById(prompt);
            return CHANNEL_WECOM_APP;
        } catch (Exception e) {
            log.warn("商机问句企微推送失败，降级为站内: promptId={} err={}", prompt.getId(), e.getMessage());
            return CHANNEL_INAPP;
        }
    }

    /** 企微 userid 口径：有映射且 enabled=1 才取值。 */
    private String wecomUserIdOf(Long ownerUserId) {
        List<EmailWeComMapping> mappings = weComMappingMapper.selectList(
                new LambdaQueryWrapper<EmailWeComMapping>()
                        .eq(EmailWeComMapping::getOwnerUserId, ownerUserId)
                        .last("limit 1"));
        if (mappings == null || mappings.isEmpty()) {
            return null;
        }
        EmailWeComMapping mapping = mappings.get(0);
        if (mapping == null || mapping.getEnabled() == null || mapping.getEnabled() != 1) {
            return null;
        }
        return mapping.getWecomUserId();
    }

    /** 今天已经给这个人发了几条问句（每人每天最多 3 条）。 */
    public long promptsSentToday(Long targetUserId) {
        if (targetUserId == null) {
            return 0;
        }
        Long count = promptMapper.selectCount(new LambdaQueryWrapper<OpportunityFollowupPrompt>()
                .eq(OpportunityFollowupPrompt::getTargetUserId, targetUserId)
                .ge(OpportunityFollowupPrompt::getSentAt, LocalDate.now().atStartOfDay()));
        return count == null ? 0 : count;
    }

    // ==================== 内部工具 ====================

    /**
     * 阶段守卫：与 {@code SalesOpportunityService#ensureStageAllowed} 同一套语义。
     *
     * <p>报价已发出不能把商机拉回「初步接触/需求确认」；进入「商务谈判/已成交」必须先有一张已批准的报价单。
     */
    void ensureStageAllowed(SalesOpportunity opportunity, String targetStatus) {
        List<Quotation> quotations = quotationMapper.selectList(new LambdaQueryWrapper<Quotation>()
                .eq(Quotation::getOpportunityId, opportunity.getId()));
        boolean hasSent = quotations != null && quotations.stream()
                .anyMatch(q -> List.of("SENT", "ACCEPTED").contains(q.getStatus()));
        boolean hasApproved = quotations != null && quotations.stream()
                .anyMatch(q -> List.of("APPROVED", "SENT", "ACCEPTED").contains(q.getStatus()));

        if (hasSent && List.of("初步接触", "需求确认").contains(targetStatus)) {
            throw new RuntimeException("报价已发送给客户，商机阶段不能回退到「" + targetStatus + "」");
        }
        if (STAGES_REQUIRING_APPROVAL.contains(targetStatus) && !hasApproved) {
            throw new RuntimeException("需先出一张已审批的报价单");
        }
    }

    /** 跟进人显示名：优先操作人姓名，其次商机负责人，都没有给系统标识（不是猜人名）。 */
    private String followerName(Long operatorUserId, Long ownerUserId) {
        for (Long userId : new Long[] {operatorUserId, ownerUserId}) {
            if (userId == null) {
                continue;
            }
            try {
                User user = userMapper.selectById(userId);
                if (user != null) {
                    String name = StringUtils.hasText(user.getRealName())
                            ? user.getRealName() : user.getUsername();
                    if (StringUtils.hasText(name)) {
                        return shorten(name, 100);
                    }
                }
            } catch (Exception e) {
                log.warn("跟进人查询失败: userId={} err={}", userId, e.getMessage());
            }
        }
        return "企微机器人";
    }

    private String toJson(OpportunityFollowUpParser.ReplyDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (Exception e) {
            log.warn("问句解析结果序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 逾期天数（今天减下次跟进日期），不逾期返回 0。 */
    public long overdueDays(SalesOpportunity opportunity, LocalDate today) {
        if (opportunity == null || opportunity.getNextFollowUpAt() == null) {
            return 0;
        }
        LocalDate base = today == null ? LocalDate.now() : today;
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                opportunity.getNextFollowUpAt().toLocalDate(), base);
        return Math.max(0, days);
    }

    /** 商机名截断，避免问句里出现超长名字。 */
    private static String shorten(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 时间描述：今日 / 已逾期 N 天 / 下次跟进 MM-dd HH:mm。 */
    private String describeTiming(SalesOpportunity opportunity) {
        if (opportunity == null || opportunity.getNextFollowUpAt() == null) {
            return "";
        }
        long days = overdueDays(opportunity, LocalDate.now());
        if (days > 0) {
            return "已逾期 " + days + " 天";
        }
        LocalDate date = opportunity.getNextFollowUpAt().toLocalDate();
        String when = LocalDate.now().equals(date) ? "今天" : NEXT_TEXT.format(opportunity.getNextFollowUpAt());
        return "原计划" + when + "跟进";
    }
}
