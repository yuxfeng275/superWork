package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.OpportunityCoach;
import com.bu.management.entity.OpportunityFollowupPrompt;
import com.bu.management.entity.Quotation;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.integration.DigestLlmClient;
import com.bu.management.mapper.OpportunityCoachMapper;
import com.bu.management.mapper.OpportunityFollowupPromptMapper;
import com.bu.management.mapper.QuotationMapper;
import com.bu.management.mapper.SalesOpportunityFollowUpMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 商机 AI 教练：给一条商机出「准备度 / 诊断 / 风险 / 该问的问题 / 打法 / 下一步」的整包建议。
 *
 * <p><b>只出建议，不改商机字段</b>：建议落 {@code opportunity_coach} 快照，采纳时才由用户显式点一下，
 * 走 {@link #adopt} 写一条跟进记录（同 {@code recordManualFollowUp} 的口径），阶段永远不会被 AI 自动推进。
 *
 * <p><b>依赖方向</b>：{@code OpportunityFollowUpService} → 本类（回复入站后入队），本类<b>绝不</b>反向注入
 * {@code OpportunityFollowUpService}（会构成循环依赖）。因此采纳落库这里直接用
 * {@link SalesOpportunityFollowUpMapper} + {@link SalesOpportunityMapper} 写。
 *
 * <p><b>异步</b>：{@link #enqueue} 只落 PENDING 行 + 丢线程池，{@link #generate} <b>不带事务</b>
 * （LLM 一次 10~30s，包在事务里会长时间占连接）。
 *
 * <p>配置项（配置组 {@code opportunity}）：{@code coach.enabled}（默认 true）、
 * {@code coach.daily-limit}（默认 10，按 {@code requested_by} 当日计数）、{@code coach.wecom-push}（默认 true）。
 *
 * @author BU Team
 * @since 2026-12-01
 */
@Slf4j
@Service
public class OpportunityCoachService {

    /** 系统配置组：商机（与问句闭环同组）。 */
    static final String CONFIG_GROUP = "opportunity";
    /** 教练总开关，默认开。 */
    static final String ENABLED_KEY = "coach.enabled";
    /** 每人每日生成上限，默认 10：LLM 按次计费，且催太勤没人看。 */
    static final String DAILY_LIMIT_KEY = "coach.daily-limit";
    /** 生成成功后是否推企微，默认开。 */
    static final String WECOM_PUSH_KEY = "coach.wecom-push";
    /** 每日上限缺省值。 */
    static final int DEFAULT_DAILY_LIMIT = 10;
    /** 单条商机列表返回的最近条数。 */
    private static final int LIST_LIMIT = 5;
    /** 上下文里带几条最近的跟进记录。 */
    private static final int CONTEXT_FOLLOW_UPS = 5;
    /** 企微外发正文上限（含标题与落款）。 */
    private static final int MARKDOWN_MAX = 500;
    /** 企微外发「诊断」一行上限。 */
    private static final int MARKDOWN_DIAGNOSIS_MAX = 80;
    /** 企微外发最多几条追问 / 几条下一步。 */
    private static final int MARKDOWN_QUESTIONS_MAX = 3;
    /** 跟进内容长度上限（与 follow_up.content 列一致）。 */
    private static final int CONTENT_MAX = 1000;
    private static final DateTimeFormatter NEXT_TEXT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter WHEN_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter WHEN_SECONDS_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 外发正文里的金额/毛利/概率数值一律抹掉：{@code 120 万}、{@code 35%}、{@code 3.5 万元} 之类。
     *
     * <p>概率建议只站内展示，企微是可能被转发给客户的通道，数字一概不出去。
     */
    private static final Pattern MONEY_OR_PERCENT = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:%|％|万元|亿元|万|亿|元)");
    /** 「成交概率 60」这种不带 % 的写法同样抹掉。 */
    private static final Pattern PROBABILITY_NUMBER = Pattern.compile(
            "(?:成交)?概率\\s*[:：]?\\s*\\d+(?:\\.\\d+)?\\s*");
    /** 抹掉后的占位符。 */
    private static final String REDACTED = "（数值略）";

    /**
     * System prompt：写死在这里而不是配置，避免运维改配置把「不许编造」这条底线改没了。
     */
    static final String SYSTEM_PROMPT = """
            你是 ToB 企业服务销售教练，服务对象是一线销售与销售主管。
            你的任务：基于给定的商机事实，判断这条商机的准备度、诊断问题、识别风险、给出该向客户问的问题、可用的打法，以及下一步动作。

            硬性要求：
            1. 只基于给定事实推理，不得编造客户没说过的信息、不得虚构人名、预算、决策链、竞品。
            2. 事实不足时，把缺口写进 readiness.missing，用 questions 去补，而不是猜。
            3. 外发给客户的话术（questions[].question、solutions[].pitch）里不得出现金额与毛利，也不得出现成交概率。
            4. 输出严格 JSON，不要 markdown 代码块，不要任何解释性前后缀。schema：
            {
              "readiness": {"score": 0-100 的整数, "missing": ["缺失的信息"]},
              "diagnosis": "一段话诊断，不超过 120 字",
              "risks": ["风险点"],
              "questions": [{"question": "要问客户的话", "why": "为什么要问", "expect": "期望听到什么"}],
              "solutions": [{"title": "打法名", "pitch": "怎么说", "evidence": "依据的商机事实"}],
              "nextActions": [{"action": "具体动作", "when": "yyyy-MM-dd HH:mm", "owner": "谁来做"}],
              "probabilitySuggestion": {"value": 0-100 的整数, "reason": "为什么"}
            }
            5. questions、solutions、nextActions 各至少 2 条、最多 4 条；nextActions 按时间先后排序。
            6. nextActions[].when 必须是 yyyy-MM-dd HH:mm 格式的具体时间，不要写「下周」「尽快」。
            """;

    private final OpportunityCoachMapper coachMapper;
    private final SalesOpportunityMapper opportunityMapper;
    private final SalesOpportunityFollowUpMapper followUpMapper;
    private final OpportunityFollowupPromptMapper promptMapper;
    private final QuotationMapper quotationMapper;
    private final DigestLlmClient digestLlmClient;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;
    private final OpportunityFollowUpSender followUpSender;
    private final Executor executor;

    public OpportunityCoachService(OpportunityCoachMapper coachMapper,
                                   SalesOpportunityMapper opportunityMapper,
                                   SalesOpportunityFollowUpMapper followUpMapper,
                                   OpportunityFollowupPromptMapper promptMapper,
                                   QuotationMapper quotationMapper,
                                   DigestLlmClient digestLlmClient,
                                   SystemConfigService systemConfigService,
                                   ObjectMapper objectMapper,
                                   OpportunityFollowUpSender followUpSender,
                                   @Qualifier("opportunityTaskExecutor") Executor executor) {
        this.coachMapper = coachMapper;
        this.opportunityMapper = opportunityMapper;
        this.followUpMapper = followUpMapper;
        this.promptMapper = promptMapper;
        this.quotationMapper = quotationMapper;
        this.digestLlmClient = digestLlmClient;
        this.systemConfigService = systemConfigService;
        this.objectMapper = objectMapper;
        this.followUpSender = followUpSender;
        this.executor = executor;
    }

    // ==================== 入队 ====================

    /**
     * 入队一次教练生成：落 PENDING 行后立即返回，真正的模型调用在线程池里做。
     *
     * <p><b>任何情况都不抛异常</b>（除开关关闭/超额/落库失败时返回 null 并记日志）：
     * 调用方是「回复入站」这种不能被教练拖累的链路。
     *
     * @param opportunityId 商机 ID
     * @param promptId      触发的问句 ID，手动生成为空
     * @param source        触发源：INBOUND / MANUAL / SCHEDULED
     * @param requestedBy   触发人（配额按它当日计数）
     * @return 新落的 PENDING 行；未入队返回 null
     */
    public OpportunityCoach enqueue(Long opportunityId, Long promptId, String source, Long requestedBy) {
        if (opportunityId == null) {
            return null;
        }
        try {
            if (!enabled()) {
                log.debug("商机 AI 教练开关关闭，跳过入队: opportunityId={}", opportunityId);
                return null;
            }
            if (reachedDailyLimit(requestedBy)) {
                log.info("商机 AI 教练已达每日上限，跳过入队: opportunityId={} requestedBy={} limit={}",
                        opportunityId, requestedBy, dailyLimit());
                return null;
            }
            OpportunityCoach row = new OpportunityCoach();
            row.setOpportunityId(opportunityId);
            row.setPromptId(promptId);
            row.setTriggerSource(normalizeSource(source));
            row.setStatus(OpportunityCoach.STATUS_PENDING);
            row.setRequestedBy(requestedBy);
            row.setCreatedAt(LocalDateTime.now());
            coachMapper.insert(row);
            try {
                executor.execute(() -> generate(row.getId()));
            } catch (Exception e) {
                // 线程池排满：把行标失败，别让前端一直看到「生成中」
                log.warn("商机 AI 教练线程池拒绝任务: coachId={} err={}", row.getId(), e.getMessage());
                markFailed(row.getId(), "生成任务入队失败：" + e.getMessage());
            }
            return row;
        } catch (Exception e) {
            log.warn("商机 AI 教练入队失败（不影响主流程）: opportunityId={} promptId={} err={}",
                    opportunityId, promptId, e.getMessage());
            return null;
        }
    }

    // ==================== 生成 ====================

    /**
     * 真正调模型生成教练包。<b>本方法不得加 @Transactional</b>：LLM 一次 10~30s，
     * 包在事务里会长时间占用数据库连接，还会把失败回写一起回滚掉。
     *
     * <p>失败一律落 FAILED + error（截 500 字），不向上抛：调用方是线程池。
     */
    public void generate(Long coachId) {
        OpportunityCoach coach = coachId == null ? null : coachMapper.selectById(coachId);
        if (coach == null) {
            log.warn("商机 AI 教练行不存在，跳过生成: coachId={}", coachId);
            return;
        }
        coach.setStatus(OpportunityCoach.STATUS_GENERATING);
        coachMapper.updateById(coach);
        try {
            SalesOpportunity opportunity = opportunityMapper.selectById(coach.getOpportunityId());
            if (opportunity == null) {
                throw new IllegalStateException("商机不存在：" + coach.getOpportunityId());
            }
            String userInput = buildContext(opportunity, coach);
            DigestLlmClient.ChatResult result =
                    digestLlmClient.chat(SYSTEM_PROMPT, userInput, this::validate);
            coach.setContentJson(objectMapper.writeValueAsString(result.json()));
            coach.setProvider(limit(result.providerCode(), 32));
            coach.setModel(limit(result.model(), 128));
            coach.setStatus(OpportunityCoach.STATUS_SUCCESS);
            coach.setError(null);
            coach.setGeneratedAt(LocalDateTime.now());
            coachMapper.updateById(coach);
            log.info("商机 AI 教练生成成功: coachId={} opportunityId={} model={}/{}",
                    coach.getId(), coach.getOpportunityId(), coach.getProvider(), coach.getModel());
            pushToWecom(coach, opportunity);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("商机 AI 教练生成失败: coachId={} opportunityId={} err={}",
                    coach.getId(), coach.getOpportunityId(), message, e);
            markFailed(coach.getId(), message);
        }
    }

    /**
     * 结构化校验：调模型时作为 strictCheck 传进去，不合格就抛异常让 {@link DigestLlmClient} 换下一个候选。
     *
     * <p>只校验「前端一定会渲染」的骨架字段，不对措辞挑刺——否则弱模型每次都被换掉，一条建议都出不来。
     */
    private void validate(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new IllegalStateException("返回内容不是 JSON 对象");
        }
        JsonNode score = json.path("readiness").path("score");
        Double value = numeric(score);
        if (value == null) {
            throw new IllegalStateException("缺少 readiness.score 数字");
        }
        if (value < 0 || value > 100) {
            throw new IllegalStateException("readiness.score 越界：" + value);
        }
        requireArray(json, "questions");
        requireArray(json, "solutions");
        requireArray(json, "nextActions");
        for (JsonNode question : json.path("questions")) {
            if (!StringUtils.hasText(text(question.path("question")))) {
                throw new IllegalStateException("questions 中有条目缺少 question");
            }
        }
    }

    // ==================== 查询 / 视图 ====================

    /** 某商机最近 5 条教练建议（新的在前）。 */
    public List<OpportunityCoach> list(Long opportunityId) {
        if (opportunityId == null) {
            return List.of();
        }
        return coachMapper.selectList(new LambdaQueryWrapper<OpportunityCoach>()
                .eq(OpportunityCoach::getOpportunityId, opportunityId)
                .orderByDesc(OpportunityCoach::getCreatedAt)
                .orderByDesc(OpportunityCoach::getId)
                .last("limit " + LIST_LIMIT));
    }

    /**
     * 把 {@code content_json} 解析成结构化 map 返前端（前端按 readiness/diagnosis/... 直接取）。
     *
     * <p>content 为空或解析失败一律返回空 map，让前端按「无建议」渲染，不抛 500。
     */
    public Map<String, Object> view(OpportunityCoach coach) {
        Map<String, Object> empty = new LinkedHashMap<>();
        JsonNode content = contentOf(coach);
        if (content == null || content.isEmpty()) {
            return empty;
        }
        try {
            return objectMapper.convertValue(content, new com.fasterxml.jackson.core.type.TypeReference<
                    LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            log.warn("商机 AI 教练内容解析失败: coachId={} err={}",
                    coach == null ? null : coach.getId(), e.getMessage());
            return empty;
        }
    }

    // ==================== 重新生成 ====================

    /**
     * 人工重新生成：已有 PENDING/GENERATING 行时直接拒绝，避免连点几下烧掉一堆额度。
     *
     * @throws IllegalStateException 已有一条在生成中
     */
    public OpportunityCoach regenerate(Long opportunityId, Long operatorUserId) {
        if (opportunityId == null) {
            throw new IllegalArgumentException("商机 ID 不能为空");
        }
        Long running = coachMapper.selectCount(new LambdaQueryWrapper<OpportunityCoach>()
                .eq(OpportunityCoach::getOpportunityId, opportunityId)
                .in(OpportunityCoach::getStatus,
                        List.of(OpportunityCoach.STATUS_PENDING, OpportunityCoach.STATUS_GENERATING)));
        if (running != null && running > 0) {
            throw new IllegalStateException("正在生成中，请稍后");
        }
        OpportunityCoach coach = enqueue(opportunityId, null, OpportunityCoach.SOURCE_MANUAL, operatorUserId);
        if (coach == null) {
            throw new IllegalStateException("商机 AI 教练未生成：请检查开关与每日上限配置");
        }
        return coach;
    }

    // ==================== 采纳 ====================

    /**
     * 采纳一条 nextActions：写一条跟进记录（来源 {@code AI_COACH}）并刷新商机快照。
     *
     * <p>口径同 {@code recordManualFollowUp}：<b>不动阶段</b>（阶段永远人工改），只刷概率沿用现值、
     * 下次跟进时间与「待跟进」状态。
     *
     * @param coachId       教练建议行
     * @param actionIndex   nextActions 下标
     * @param operatorUserId 操作人（只用于日志，无对应列）
     * @throws IllegalArgumentException 建议不存在、下标越界、动作内容为空、商机不存在
     */
    public SalesOpportunityFollowUp adopt(Long coachId, int actionIndex, Long operatorUserId) {
        OpportunityCoach coach = coachId == null ? null : coachMapper.selectById(coachId);
        if (coach == null) {
            throw new IllegalArgumentException("教练建议不存在");
        }
        JsonNode content = contentOf(coach);
        JsonNode actions = content == null ? null : content.path("nextActions");
        if (actions == null || !actions.isArray() || actionIndex < 0 || actionIndex >= actions.size()) {
            throw new IllegalArgumentException("建议序号越界：" + actionIndex);
        }
        JsonNode node = actions.get(actionIndex);
        String action = text(node.path("action"));
        if (!StringUtils.hasText(action)) {
            throw new IllegalArgumentException("该条建议内容为空");
        }
        SalesOpportunity opportunity = opportunityMapper.selectById(coach.getOpportunityId());
        if (opportunity == null) {
            throw new IllegalArgumentException("商机不存在");
        }
        String whenText = text(node.path("when"));
        LocalDateTime nextAt = parseWhen(whenText);

        LocalDateTime now = LocalDateTime.now();
        SalesOpportunityFollowUp item = new SalesOpportunityFollowUp();
        item.setOpportunityId(opportunity.getId());
        item.setFollowUpAt(now);
        item.setFollower("AI 教练");
        item.setContent(limit("AI 教练建议：" + action.strip(), CONTENT_MAX));
        item.setStatus(limit(opportunity.getStatus(), 30));
        item.setProbability(opportunity.getProbability() == null ? 0 : opportunity.getProbability());
        item.setNextFollowUp(limit(nextAt == null
                ? (StringUtils.hasText(whenText) ? whenText.strip() : null)
                : NEXT_TEXT.format(nextAt), 100));
        item.setSource(OpportunityFollowUpService.SOURCE_AI_COACH);
        item.setPromptId(null);
        item.setCreatedAt(now);
        followUpMapper.insert(item);

        // 商机快照：只刷跟进时间与状态，概率/阶段保持人工口径
        opportunity.setLastFollowUpAt(now);
        opportunity.setFollowUpState("PENDING");
        if (nextAt != null) {
            opportunity.setNextFollowUpAt(nextAt);
            opportunity.setNextFollowUp(item.getNextFollowUp());
        }
        opportunityMapper.updateById(opportunity);
        log.info("商机 AI 教练建议已采纳: coachId={} opportunityId={} actionIndex={} operator={}",
                coachId, opportunity.getId(), actionIndex, operatorUserId);
        return item;
    }

    // ==================== 企微外发 ====================

    /**
     * 拼企微 markdown 摘要（正文 ≤500 字）。
     *
     * <p><b>内容边界</b>：只有「一行诊断 + 最多 3 条追问 + 最多 1 条下一步 + 一句站内引导」；
     * <b>金额、毛利、成交概率数值一律不出去</b>——企微会掉落进对话流，可能被截屏转发给客户，
     * 概率建议只站内展示，所以这里连 probabilitySuggestion 都不读，另外对模型措辞再做一遍数值抹除。
     */
    public String buildWecomMarkdown(OpportunityCoach coach) {
        JsonNode content = contentOf(coach);
        StringBuilder sb = new StringBuilder("**商机 AI 教练**\n");
        String diagnosis = clean(content == null ? null : text(content.path("diagnosis")));
        if (StringUtils.hasText(diagnosis)) {
            sb.append("诊断：").append(clip(diagnosis, MARKDOWN_DIAGNOSIS_MAX)).append('\n');
        }
        JsonNode questions = content == null ? null : content.path("questions");
        int used = 0;
        if (questions != null && questions.isArray()) {
            for (JsonNode question : questions) {
                if (used >= MARKDOWN_QUESTIONS_MAX) {
                    break;
                }
                String line = clean(text(question.path("question")));
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                sb.append("问：").append(clip(line, 100)).append('\n');
                used++;
            }
        }
        JsonNode actions = content == null ? null : content.path("nextActions");
        if (actions != null && actions.isArray() && !actions.isEmpty()) {
            JsonNode first = actions.get(0);
            String action = clean(text(first.path("action")));
            if (StringUtils.hasText(action)) {
                String when = clean(text(first.path("when")));
                sb.append("下一步：").append(clip(action, 100));
                if (StringUtils.hasText(when)) {
                    sb.append("（").append(clip(when, 24)).append("）");
                }
                sb.append('\n');
            }
        }
        sb.append("详细建议见合拍商机详情");
        return clip(sb.toString(), MARKDOWN_MAX);
    }

    /** 生成成功后按需推企微（只在问句入站触发的场景推，手动生成不打扰）；任何异常都吞掉。 */
    private void pushToWecom(OpportunityCoach coach, SalesOpportunity opportunity) {
        if (coach.getPromptId() == null || !wecomPushEnabled()) {
            return;
        }
        try {
            String markdown = buildWecomMarkdown(coach);
            String channel = followUpSender.pushMarkdown(coach.getOpportunityId(),
                    coach.getRequestedBy() == null ? opportunity.getOwnerUserId() : coach.getRequestedBy(),
                    markdown);
            log.info("商机 AI 教练摘要已下发: coachId={} opportunityId={} channel={}",
                    coach.getId(), coach.getOpportunityId(), channel);
        } catch (Exception e) {
            log.warn("商机 AI 教练摘要下发失败（建议已落库，不影响）: coachId={} err={}",
                    coach.getId(), e.getMessage());
        }
    }

    // ==================== 上下文组装 ====================

    /**
     * 拼给模型的 userInput：商机字段 + 最近 5 条跟进 + 本次触发的问句与回复 + 报价概况。
     *
     * <p>紧凑文本块而不是 JSON：多数候选模型对键值文本的遵循度更稳，也省 token。
     */
    private String buildContext(SalesOpportunity opportunity, OpportunityCoach coach) {
        StringBuilder sb = new StringBuilder();
        sb.append("【商机】\n")
                .append("名称：").append(nz(opportunity.getName())).append('\n')
                .append("客户：").append(nz(opportunity.getCustomer())).append('\n')
                .append("阶段：").append(nz(opportunity.getStatus())).append('\n')
                .append("金额：").append(opportunity.getAmount() == null ? "未定" : opportunity.getAmount().toPlainString())
                .append(" 元\n")
                .append("当前成交概率：").append(opportunity.getProbability() == null ? "未填" : opportunity.getProbability())
                .append("%\n")
                .append("下次跟进：").append(timing(opportunity.getNextFollowUpAt())).append('\n')
                .append("负责人：").append(nz(opportunity.getOwner())).append('\n')
                .append("业务线：").append(nz(opportunity.getBusinessLine())).append('\n');
        if (StringUtils.hasText(opportunity.getNote())) {
            sb.append("备注：").append(clip(opportunity.getNote().strip(), 300)).append('\n');
        }

        sb.append("\n【最近跟进】\n");
        List<SalesOpportunityFollowUp> followUps = recentFollowUps(opportunity.getId());
        if (followUps.isEmpty()) {
            sb.append("（暂无跟进记录：这是冷商机，先确认进展与决策链）\n");
        } else {
            for (SalesOpportunityFollowUp item : followUps) {
                sb.append(item.getFollowUpAt() == null ? "-" : NEXT_TEXT.format(item.getFollowUpAt()))
                        .append(' ')
                        .append(nz(item.getFollower()))
                        .append("：")
                        .append(clip(item.getContent(), 200));
                if (item.getProbability() != null) {
                    sb.append("（概率 ").append(item.getProbability()).append("%）");
                }
                sb.append('\n');
            }
        }

        if (coach.getPromptId() != null) {
            OpportunityFollowupPrompt prompt = promptMapper.selectById(coach.getPromptId());
            if (prompt != null) {
                sb.append("\n【本次触发的问句】\n");
                sb.append(clip(prompt.getQuestionText(), 500)).append('\n');
                sb.append("\n【客户的回复原文】\n");
                sb.append(StringUtils.hasText(prompt.getReplyText())
                        ? clip(prompt.getReplyText(), 800) : "（未记录回复）").append('\n');
            }
        }

        List<Quotation> quotations = quotationMapper.selectList(new LambdaQueryWrapper<Quotation>()
                .eq(Quotation::getOpportunityId, opportunity.getId()));
        sb.append("\n【报价】\n");
        if (quotations == null || quotations.isEmpty()) {
            sb.append("尚无报价单\n");
        } else {
            Quotation latest = quotations.stream()
                    .filter(q -> q.getQuoteDate() != null)
                    .max(java.util.Comparator.comparing(Quotation::getQuoteDate))
                    .orElse(quotations.get(0));
            sb.append("共 ").append(quotations.size()).append(" 条；最近一次：状态 ")
                    .append(nz(latest.getStatus()))
                    .append("，报价日期 ")
                    .append(latest.getQuoteDate() == null ? "未填" : DAY_TEXT.format(latest.getQuoteDate()))
                    .append('\n');
        }
        return sb.toString();
    }

    /** 最近 5 条跟进记录（新的在前）。查询异常返回空列表：上下文缺一块也不该让生成失败。 */
    private List<SalesOpportunityFollowUp> recentFollowUps(Long opportunityId) {
        try {
            List<SalesOpportunityFollowUp> rows = followUpMapper.selectList(
                    new LambdaQueryWrapper<SalesOpportunityFollowUp>()
                            .eq(SalesOpportunityFollowUp::getOpportunityId, opportunityId)
                            .orderByDesc(SalesOpportunityFollowUp::getFollowUpAt)
                            .orderByDesc(SalesOpportunityFollowUp::getId)
                            .last("limit " + CONTEXT_FOLLOW_UPS));
            return rows == null ? new ArrayList<>() : rows;
        } catch (Exception e) {
            log.warn("商机 AI 教练上下文取跟进记录失败: opportunityId={} err={}",
                    opportunityId, e.getMessage());
            return new ArrayList<>();
        }
    }

    // ==================== 配置 ====================

    /** 教练总开关，读失败按开（守住「配错了也别整条链路静默」）。 */
    private boolean enabled() {
        try {
            return systemConfigService.getBoolean(CONFIG_GROUP, ENABLED_KEY, true);
        } catch (Exception e) {
            log.warn("商机 AI 教练开关读取失败，按开启处理: err={}", e.getMessage());
            return true;
        }
    }

    /** 生成后是否推企微，读失败按开。 */
    private boolean wecomPushEnabled() {
        try {
            return systemConfigService.getBoolean(CONFIG_GROUP, WECOM_PUSH_KEY, true);
        } catch (Exception e) {
            log.warn("商机 AI 教练企微推送开关读取失败，按开启处理: err={}", e.getMessage());
            return true;
        }
    }

    /** 每日上限：非数字/小于 0 一律回缺省 10；0 表示不限量。 */
    private int dailyLimit() {
        try {
            String value = systemConfigService.getValue(CONFIG_GROUP, DAILY_LIMIT_KEY,
                    String.valueOf(DEFAULT_DAILY_LIMIT));
            if (!StringUtils.hasText(value)) {
                return DEFAULT_DAILY_LIMIT;
            }
            int parsed = Integer.parseInt(value.strip());
            return parsed < 0 ? DEFAULT_DAILY_LIMIT : parsed;
        } catch (Exception e) {
            log.warn("商机 AI 教练每日上限读取失败，按缺省 {} 处理: err={}", DEFAULT_DAILY_LIMIT, e.getMessage());
            return DEFAULT_DAILY_LIMIT;
        }
    }

    /** 该触发人今天是否已经用满额度（不限量返回 false；读库失败按未超额，靠线程池与开关兜）。 */
    private boolean reachedDailyLimit(Long requestedBy) {
        int limit = dailyLimit();
        if (limit <= 0) {
            return false;
        }
        LocalDateTime start = LocalDate.now().atStartOfDay();
        try {
            LambdaQueryWrapper<OpportunityCoach> query = new LambdaQueryWrapper<OpportunityCoach>()
                    .ge(OpportunityCoach::getCreatedAt, start)
                    .lt(OpportunityCoach::getCreatedAt, start.plusDays(1));
            if (requestedBy != null) {
                query.eq(OpportunityCoach::getRequestedBy, requestedBy);
            }
            Long used = coachMapper.selectCount(query);
            return used != null && used >= limit;
        } catch (Exception e) {
            log.warn("商机 AI 教练配额统计失败，按未超额处理: err={}", e.getMessage());
            return false;
        }
    }

    // ==================== 内部工具 ====================

    /** 置 FAILED + error（截 500）；回写本身失败只记日志，不递归抛。 */
    private void markFailed(Long coachId, String message) {
        try {
            OpportunityCoach row = coachMapper.selectById(coachId);
            if (row == null) {
                return;
            }
            row.setStatus(OpportunityCoach.STATUS_FAILED);
            row.setError(limit(message == null ? "生成失败" : message, 500));
            row.setGeneratedAt(LocalDateTime.now());
            coachMapper.updateById(row);
        } catch (Exception e) {
            log.warn("商机 AI 教练失败状态回写失败: coachId={} err={}", coachId, e.getMessage());
        }
    }

    /** 解析 content_json；为空/解析失败返回空对象节点（调用方统一走 path 取值，不必判空）。 */
    private JsonNode contentOf(OpportunityCoach coach) {
        JsonNode empty = objectMapper.createObjectNode();
        if (coach == null || !StringUtils.hasText(coach.getContentJson())) {
            return empty;
        }
        try {
            JsonNode json = objectMapper.readTree(coach.getContentJson());
            return json == null || !json.isObject() ? empty : json;
        } catch (Exception e) {
            log.warn("商机 AI 教练内容 JSON 解析失败: coachId={} err={}",
                    coach.getId(), e.getMessage());
            return empty;
        }
    }

    /** 数组型必填字段校验：必须是数组且有内容。 */
    private void requireArray(JsonNode json, String field) {
        JsonNode node = json.path(field);
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalStateException(field + " 必须是非空数组");
        }
    }

    /** 取数字：数字节点直接用，字符串尝试解析；都不是返回 null。 */
    private Double numeric(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText().strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 取文本：缺失/非文本返回 null，避免 {@code "null"} 这种字面量跑到提示词里。 */
    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.asText(null);
    }

    /**
     * 解析 nextActions[].when：支持 ISO（{@code 2026-12-01T10:00}）、
     * {@code yyyy-MM-dd HH:mm}、带秒写法，以及纯日期（当天 09:00）；解析不出当没给。
     */
    private LocalDateTime parseWhen(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.strip();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // 继续尝试下面的格式
        }
        for (DateTimeFormatter formatter : List.of(WHEN_TEXT, WHEN_SECONDS_TEXT)) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 继续尝试下一个格式
            }
        }
        try {
            return LocalDate.parse(text).atTime(9, 0);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** 外发前的数值抹除：金额、毛利、百分比、成交概率数字全部替换为占位符。 */
    private String clean(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String result = MONEY_OR_PERCENT.matcher(value.strip()).replaceAll(REDACTED);
        result = PROBABILITY_NUMBER.matcher(result).replaceAll("概率" + REDACTED);
        return result;
    }

    /** 触发源归一到三个常量，未知值按 MANUAL 记，避免脏值进表。 */
    private String normalizeSource(String source) {
        if (!StringUtils.hasText(source)) {
            return OpportunityCoach.SOURCE_MANUAL;
        }
        String value = source.strip().toUpperCase();
        return List.of(OpportunityCoach.SOURCE_INBOUND, OpportunityCoach.SOURCE_MANUAL,
                OpportunityCoach.SOURCE_SCHEDULED).contains(value)
                ? value : OpportunityCoach.SOURCE_MANUAL;
    }

    /** 下次跟进时间的文本口径。 */
    private String timing(LocalDateTime time) {
        return time == null ? "未排" : WHEN_TEXT.format(time);
    }

    private String nz(String value) {
        return StringUtils.hasText(value) ? value.strip() : "未填";
    }

    /** 截断（不抛异常）。 */
    private String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 截断，空值返回 null（写库列用）。 */
    private String limit(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return clip(value, max);
    }
}
