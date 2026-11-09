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
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 商机跟进闭环：问句生成 → 企微/站内下发 → 回复解析 → 人工确认落库。
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

    /** 提醒阶段标签。 */
    public static final String STAGE_DUE = "今日跟进";
    public static final String STAGE_OVERDUE = "逾期跟进";
    public static final String STAGE_SILENT = "静默唤醒";
    public static final String STAGE_QUOTATION_SILENT = "报价静默";

    /** 进入这两个阶段必须已有已批准的报价单（与 SalesOpportunityService 保持一致）。 */
    private static final List<String> STAGES_REQUIRING_APPROVAL = List.of("商务谈判", "已成交");
    /** 已收口的商机不再催跟进。 */
    private static final List<String> CLOSED_STATUSES = List.of("已成交", "已输单", "已关闭");
    /** 报价静默关注的阶段。 */
    private static final List<String> QUOTATION_STAGES = List.of("方案报价", "商务谈判");
    /** 回复只认 24 小时内最新一条已发送问句。 */
    private static final long REPLY_WINDOW_HOURS = 24;
    private static final int MAX_NAME = 12;
    private static final DateTimeFormatter NEXT_TEXT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter DEDUPE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SalesOpportunityMapper opportunityMapper;
    private final SalesOpportunityFollowUpMapper followUpMapper;
    private final OpportunityFollowupPromptMapper promptMapper;
    private final QuotationMapper quotationMapper;
    private final EmailWeComMappingMapper weComMappingMapper;
    private final UserMapper userMapper;
    private final WeComClient weComClient;
    private final ObjectMapper objectMapper;

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

    /** 未收口 + 未静音的商机查询基座。 */
    private LambdaQueryWrapper<SalesOpportunity> open() {
        return new LambdaQueryWrapper<SalesOpportunity>()
                .eq(SalesOpportunity::getRemindMuted, 0)
                .notIn(SalesOpportunity::getStatus, CLOSED_STATUSES);
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
     * @param msgId         消息 ID，仅用于日志与排查
     * @param channel       入站渠道（WECOM_APP / INAPP）
     * @return 被更新的问句；没有可处理问句时返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public OpportunityFollowupPrompt applyReply(String senderKey, Long opportunityId, String text,
                                                String msgId, String channel) {
        if (opportunityId == null || !StringUtils.hasText(text)) {
            return null;
        }
        LocalDateTime since = LocalDateTime.now().minusHours(REPLY_WINDOW_HOURS);
        OpportunityFollowupPrompt prompt = promptMapper.selectOne(
                new LambdaQueryWrapper<OpportunityFollowupPrompt>()
                        .eq(OpportunityFollowupPrompt::getOpportunityId, opportunityId)
                        .eq(OpportunityFollowupPrompt::getState, OpportunityFollowupPrompt.STATE_SENT)
                        .ge(OpportunityFollowupPrompt::getSentAt, since)
                        .orderByDesc(OpportunityFollowupPrompt::getSentAt)
                        .orderByDesc(OpportunityFollowupPrompt::getId)
                        .last("limit 1"));
        if (prompt == null) {
            log.info("商机回复没有可处理的问句，忽略: oppId={} msgId={} channel={}", opportunityId, msgId, channel);
            return null;
        }
        String reply = shorten(text.strip(), 2000);
        OpportunityFollowUpParser.ReplyDraft draft = OpportunityFollowUpParser.parseReply(reply);
        prompt.setRepliedAt(LocalDateTime.now());
        prompt.setReplyText(reply);
        if (draft != null && (draft.stage() != null || draft.probability() != null || draft.nextAt() != null)) {
            prompt.setState(OpportunityFollowupPrompt.STATE_PARSED);
            prompt.setParsedJson(toJson(draft));
        } else {
            prompt.setState(OpportunityFollowupPrompt.STATE_REPLIED);
            prompt.setParsedJson(draft == null ? null : toJson(draft));
        }
        if (StringUtils.hasText(senderKey) && !StringUtils.hasText(prompt.getWecomUserid())) {
            prompt.setWecomUserid(shorten(senderKey, 64));
        }
        promptMapper.updateById(prompt);
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
