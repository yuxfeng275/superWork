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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
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
    /**
     * 可配置知识包（Markdown：产品能力 / 标准方案 / 成功案例 / 报价口径），由运营维护。
     *
     * <p>没有这块时模型只能空想产品与案例，追问和方案就特别笼统；配了就以内部参考塞在 userInput 末尾。
     */
    static final String KNOWLEDGE_KEY = "coach.knowledge";
    /** 每日上限缺省值。 */
    static final int DEFAULT_DAILY_LIMIT = 10;
    /** 单条商机列表返回的最近条数。 */
    private static final int LIST_LIMIT = 5;
    /**
     * 上下文里带几条最近的跟进记录：3 条足够交代来龙去脉。
     * 5 条 × 200 字只是把提示词撑长、把模型带歪（它会拿 9 个月前的旧事实当现状）。
     */
    private static final int CONTEXT_FOLLOW_UPS = 3;
    /** 上下文超预算时历史条数的下限。 */
    private static final int CONTEXT_FOLLOW_UPS_MIN = 2;
    /** 单条跟进写进上下文的正文字数上限。 */
    private static final int CONTEXT_FOLLOW_UP_MAX = 120;
    /** 商机备注写进上下文的正文字数上限。 */
    private static final int CONTEXT_NOTE_MAX = 200;
    /** userInput 字符预算：超了就把历史条数往下砍，输入越短首字越快。 */
    private static final int CONTEXT_BUDGET = 1200;
    /** 运营维护的方案/案例知识包正文上限。 */
    private static final int KNOWLEDGE_MAX = 2000;
    /** when 允许的最大前瞻天数：再远的时间基本是模型凑数，一律留空让人自己定。 */
    private static final int WHEN_MAX_DAYS = 180;
    /** when 被判不可用时，追加到动作文本末尾的占位（前端与 markdown 都不展示时间括号）。 */
    static final String WHEN_TBD = "（时间待定）";
    /** 兜底巡检单次最多重跑几行：一次都是 10~30s 的模型调用，扫多了会把线程和预算一起磨穿。 */
    private static final int SWEEP_LIMIT = 20;
    /** 企微外发正文上限（含标题与落款）。 */
    private static final int MARKDOWN_MAX = 500;
    /** 企微外发「诊断」一行上限。 */
    private static final int MARKDOWN_DIAGNOSIS_MAX = 80;
    /** 企微外发最多几条追问 / 几条下一步。 */
    private static final int MARKDOWN_QUESTIONS_MAX = 3;
    /**
     * 一个教练包各段最多几条：条数越多输出越长、首字越慢，而且第 4 条基本都是凑数的。
     * 提示词里也写了同一组上限，这里是模型不听话时的硬截断。
     */
    private static final int MAX_NEXT_ACTIONS = 3;
    /** 打法最多 2 条。 */
    private static final int MAX_SOLUTIONS = 2;
    /** 追问最多 3 条。 */
    private static final int MAX_QUESTIONS = 3;
    /**
     * nextActions[].owner 的三个角色名：模型只能从这三个里挑，或写本次商机负责人。
     * 其它人名一律当幻觉，落库前改写成「销售」。
     */
    static final String OWNER_SALES = "销售";
    /** owner 角色名：客户侧。 */
    static final String OWNER_CUSTOMER = "客户";
    /** owner 角色名：本次触发的销售本人。 */
    static final String OWNER_ME = "我";
    /** 跟进内容长度上限（与 follow_up.content 列一致）。 */
    private static final int CONTENT_MAX = 1000;
    private static final DateTimeFormatter NEXT_TEXT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter WHEN_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter WHEN_SECONDS_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 星期几的中文写法（如「星期二」）：模型也需要知道今天是周几才好排跟进。 */
    private static final DateTimeFormatter WEEK_TEXT =
            DateTimeFormatter.ofPattern("EEEE", Locale.CHINA);
    /**
     * 从 when 里抠出「年（可缺）月日时分」：容忍 {@code 2025-11-12 10:00}、{@code 2025/11/12 10:00}、
     * {@code 2025年11月12日 10:00}、{@code 2026-12-01T10:00} 以及只有 {@code 11-12 10:00} 的写法。
     *
     * <p>用正则而不是一堆 DateTimeFormatter：模型给的格式几乎没有一致过，正则一次就能兜住，
     * 而且「缺年份」不会被解析器当成异常，可以直接补今年。
     */
    private static final Pattern WHEN_PARTS = Pattern.compile(
            "(?:(\\d{4})[-/.年])?(\\d{1,2})[-/.月](\\d{1,2})日?(?:[ T]+(\\d{1,2})[:：]?(\\d{1,2})?)?");

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
            5. questions、solutions、nextActions 各至少 2 条；条数上限：questions 最多 3 条、
                 solutions 最多 2 条、nextActions 最多 3 条；nextActions 按时间先后排序。
            6. nextActions[].when 必须是 yyyy-MM-dd HH:mm 格式的具体时间，不要写「下周」「尽快」。
            7. nextActions[].owner 只能取以下值之一：销售、客户、我，或本次给定的商机负责人姓名；
               绝对不得出现其它人名（不得写「王总」「李经理」这类看似具体的人名），需要具体人时写 销售。
            """;

    /**
     * 拼本次的系统提示：底线条款是常量（防运维改配置），只把「今天是几号」这类参数拼进去。
     *
     * <p><b>为什么必须告诉模型今天</b>：跟进记录只落「月-日 时:分」，没有年份；模型自身的知识截止
     * 又早于当前日期，于是会把 {@code nextActions[].when} 猜成去年（实测写成 2025，而今年是 2026）。
     * 这里明确给出今天与固定年份，再由 {@link #normalizeDates} 在写库前兜第二道。
     *
     * <p>用字符串拼接而不是 text block 占位符插值：插值容易把 {@code """} 与格式符搅在一起，编译期就炸。
     */
    static String systemPrompt(LocalDate today) {
        return systemPrompt(today, null);
    }

    /**
     * 同上，并把「本次商机负责人」写进提示词。
     *
     * <p><b>为什么点名道姓</b>：不点名时模型会自己补人名（实测 {@code owner:"王赢"}、{@code "刘晓文"}，
     * 上下文里根本没这两个人，真实负责人是「王瀛」）。给了允许值，编造才有个明确的否定边界。
     *
     * @param today     今天（可空，空则取系统日期）
     * @param ownerName 商机负责人姓名（可空）
     */
    static String systemPrompt(LocalDate today, String ownerName) {
        LocalDate day = today == null ? LocalDate.now() : today;
        String owner = StringUtils.hasText(ownerName) ? ownerName.strip() : "";
        String ownerLine = owner.isEmpty()
                ? "11. 本次未给定商机负责人：nextActions[].owner 只能写 销售 / 客户 / 我，不得写具体人名。\n"
                : "11. 本次商机负责人是「" + owner + "」：nextActions[].owner 只能写 销售 / 客户 / 我 / "
                        + owner + "，写其它人名一律视为错误。\n";
        return SYSTEM_PROMPT
                + "8. 今天是 " + DAY_TEXT.format(day) + "；所有日期必须是今天及以后，年份固定为 "
                + day.getYear() + "，返回一年前的日期视为错误。\n"
                + "9. 不要把【历史跟进】里超过 14 天的记录当成当前状态：它们只是背景，"
                + "若与本次回复冲突，以【本次回复】为准。\n"
                + "10. 若给了【已知方案与案例】，优先复用其中真实的产品能力与案例口径，不得编造；"
                + "转述时不得写出客户名，也不得把内部报价口径直接写进给客户的话术。\n"
                + ownerLine;
    }

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
        // 幂等：兜底巡检与线程池可能同时触达同一行，已出结果（SUCCESS/FAILED）或已在生成中的行直接返回，
        // 既不重复调模型，也不把状态来回改写成 GENERATING
        String current = coach.getStatus();
        if (OpportunityCoach.STATUS_SUCCESS.equals(current)
                || OpportunityCoach.STATUS_FAILED.equals(current)
                || OpportunityCoach.STATUS_GENERATING.equals(current)) {
            log.debug("商机 AI 教练行已是终态或生成中，跳过生成: coachId={} status={}", coachId, current);
            return;
        }
        coach.setStatus(OpportunityCoach.STATUS_GENERATING);
        coachMapper.updateById(coach);
        try {
            SalesOpportunity opportunity = opportunityMapper.selectById(coach.getOpportunityId());
            if (opportunity == null) {
                throw new IllegalStateException("商机不存在：" + coach.getOpportunityId());
            }
            LocalDate today = LocalDate.now();
            String userInput = buildContext(opportunity, coach, today);
            DigestLlmClient.ChatResult result =
                    digestLlmClient.chat(systemPrompt(today, opportunity.getOwner()), userInput, this::validate);
            // 落库前先把 when 的年份与越界、owner 的编造人名纠正掉：前端会原样展示给客户
            coach.setContentJson(objectMapper.writeValueAsString(
                    normalizeDates(result.json(), today, opportunity.getOwner())));
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

    // ==================== 兜底巡检 ====================

    /**
     * 兜底巡检：扫「2 分钟前入队、至今仍是 PENDING」的行并逐行重跑 {@link #generate}。
     *
     * <p>存在意义：入队与事务的竞态（异步线程抢在提交前查不到行）、线程池丢任务、实例重启
     * 都会留下一行永远 PENDING 却没人生成的死数据。这里每 2 分钟清扫一次，单行失败不影响其它行。
     *
     * <p>只扫 PENDING：GENERATING 归线程池管，SUCCESS/FAILED 已经出过结果，{@link #generate} 也会再幂等拦一次。
     */
    @Scheduled(fixedDelay = 120_000, zone = "Asia/Shanghai")
    public void sweepPending() {
        List<OpportunityCoach> stalled;
        try {
            stalled = coachMapper.selectList(new LambdaQueryWrapper<OpportunityCoach>()
                    .eq(OpportunityCoach::getStatus, OpportunityCoach.STATUS_PENDING)
                    .lt(OpportunityCoach::getCreatedAt, LocalDateTime.now().minusMinutes(2))
                    .orderByAsc(OpportunityCoach::getId)
                    .last("limit " + SWEEP_LIMIT));
        } catch (Exception e) {
            // 定时任务里抛异常会被调度器吞掉，这里主动记一笔，否则问题永远无声
            log.warn("商机 AI 教练巡检查询失败: {}", e.getMessage());
            return;
        }
        if (stalled == null || stalled.isEmpty()) {
            return;
        }
        int generated = 0;
        for (OpportunityCoach row : stalled) {
            if (row == null || row.getId() == null) {
                continue;
            }
            try {
                generate(row.getId());
                generated++;
            } catch (Exception e) {
                // 单行炸掉不能拖垮整轮：后面的行还要扫
                log.warn("商机 AI 教练巡检单行失败: coachId={} err={}", row.getId(), e.getMessage());
            }
        }
        log.info("商机 AI 教练巡检完成: 扫到={} 重跑={}", stalled.size(), generated);
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

    // ==================== 落库前纠正（防幻觉） ====================

    /**
     * 落库前对模型输出做一次修正：{@code nextActions[].when} 的年份与越界、概率与准备度分数。
     *
     * <p><b>为什么必须做</b>：模型的知识截止早于今天，最典型的幻觉就是把下一步时间写成去年
     * （实测「2025-11-12 10:00」，今年是 2026）。前端会把 when 原样展示，企微摘要也会转发出去，
     * 所以宁可把时间清空，也不让一个错的日期漂到客户面前。
     *
     * <p><b>规则</b>：年份 ≠ 今年 → 强制改成今年；改完仍早于今天 00:00 或晚于今天 + 180 天 →
     * when 置空、动作文本追加 {@link #WHEN_TBD}；解析异常一律当空，<b>绝不抛异常</b>——
     * 日子写歪是模型的常态，不该让整次生成失败。
     *
     * <p>入参是 {@code result.json()} 的深拷贝，改完再交给 {@code writeValueAsString}
     * （{@code content_json} 是 String，所以只能在序列化前改副本）。
     *
     * <p>同一个方法里还兜另外两件模型说不听的：{@code nextActions[].owner} 只允许「销售 / 客户 / 我 /
     * 本次商机负责人」，其它人名一律改写成「销售」（实测模型会凭空编出 {@code 王赢}、{@code 刘晓文}）；
     * 以及各数组的条数上限（超出的直接截掉，条数越多输出越慢越跑题）。
     *
     * @param json      模型返回的 JSON 对象
     * @param today     今天（可空，空则取系统日期）
     * @param ownerName 本次商机负责人姓名（可空）：加进 owner 白名单
     * @return 修正后的副本；入参不是对象时原样返回
     */
    JsonNode normalizeDates(JsonNode json, LocalDate today) {
        return normalizeDates(json, today, null);
    }

    /** 同上，额外把「本次商机负责人」放进 owner 白名单。 */
    JsonNode normalizeDates(JsonNode json, LocalDate today, String ownerName) {
        if (json == null || !json.isObject()) {
            return json;
        }
        LocalDate day = today == null ? LocalDate.now() : today;
        JsonNode copied = json.deepCopy();
        if (!(copied instanceof ObjectNode root)) {
            return json;
        }
        // 先按条数上限截断，再逐条纠正：白名单与日期只修真正会落库的那几条
        trimArray(root, "questions", MAX_QUESTIONS);
        trimArray(root, "solutions", MAX_SOLUTIONS);
        trimArray(root, "nextActions", MAX_NEXT_ACTIONS);
        JsonNode actions = root.path("nextActions");
        if (actions.isArray()) {
            Set<String> owners = ownerWhitelist(ownerName);
            for (JsonNode action : actions) {
                if (action instanceof ObjectNode node) {
                    fixWhen(node, day);
                    fixOwner(node, owners);
                }
            }
        }
        // 概率建议与准备度分数同口径截断：越界的数字会让前端进度条画歪，也可能被读成承诺
        clampScore(root.path("probabilitySuggestion"), "value");
        clampScore(root.path("readiness"), "score");
        return root;
    }

    /** 修正单条 nextActions：能救的改成今年的合法时间，救不了的清空并标注「时间待定」。 */
    private void fixWhen(ObjectNode action, LocalDate day) {
        String raw = text(action.path("when"));
        if (!StringUtils.hasText(raw)) {
            // 模型没给时间：保持不展示，也不硬编一个
            action.remove("when");
            return;
        }
        LocalDateTime parsed = parseLooseWhen(raw, day);
        if (parsed == null) {
            markWhenUnknown(action);
            return;
        }
        // 年份不等于今年的一律拉回今年：模型最常错的就是这一格
        if (parsed.getYear() != day.getYear()) {
            try {
                parsed = parsed.withYear(day.getYear());
            } catch (RuntimeException e) {
                markWhenUnknown(action);
                return;
            }
        }
        if (parsed.isBefore(day.atStartOfDay())
                || parsed.isAfter(day.plusDays(WHEN_MAX_DAYS).atTime(23, 59))) {
            markWhenUnknown(action);
            return;
        }
        action.put("when", WHEN_TEXT.format(parsed));
    }

    /**
     * 修正单条 nextActions 的 owner：只允许「销售 / 客户 / 我 / 本次商机负责人」，其它一律改写成「销售」。
     *
     * <p><b>为什么这么硬</b>：owner 会被前端原样展示，也可能跟着企微摘要转发出去。模型爱把常识里的
     * 中文名顺手写进来——实测给的是 {@code "王赢"}、{@code "刘晓文"}，而上下文里根本没有这两个人
     * （真实负责人是「王瀛」）。编一个人名出去比不给名字更糟，所以宁可降级成角色名。
     */
    private void fixOwner(ObjectNode action, Set<String> allowed) {
        String raw = text(action.path("owner"));
        String value = raw == null ? "" : raw.strip();
        if (allowed.contains(value)) {
            if (!value.equals(raw)) {
                // 允许但带空格：顺手去掉，否则前端展示会多出空隙
                action.put("owner", value);
            }
            return;
        }
        action.put("owner", OWNER_SALES);
    }

    /** owner 允许集：三个角色名 + 商机负责人（原样与去掉空白两种写法）。 */
    private Set<String> ownerWhitelist(String ownerName) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(OWNER_SALES);
        allowed.add(OWNER_CUSTOMER);
        allowed.add(OWNER_ME);
        if (StringUtils.hasText(ownerName)) {
            allowed.add(ownerName.strip());
            allowed.add(ownerName.strip().replaceAll("\\s+", ""));
        }
        return allowed;
    }

    /** 数组超长时只留前 {@code max} 条（模型常无视条数上限）。 */
    private void trimArray(ObjectNode root, String field, int max) {
        JsonNode node = root.path(field);
        if (!node.isArray() || node.size() <= max) {
            return;
        }
        ArrayNode kept = objectMapper.createArrayNode();
        for (JsonNode item : node) {
            if (kept.size() >= max) {
                break;
            }
            kept.add(item);
        }
        root.set(field, kept);
    }

    /** when 置空字符串（前端与 markdown 都不再打印时间括号），并把「待定」写进动作文本。 */
    private void markWhenUnknown(ObjectNode action) {
        action.put("when", "");
        String name = text(action.path("action"));
        if (StringUtils.hasText(name) && !name.contains(WHEN_TBD)) {
            action.put("action", name + WHEN_TBD);
        }
    }

    /** 宽松解析 when：抠出年月日时分，缺年按今年补；任何异常都返回 null（当没给）。 */
    private LocalDateTime parseLooseWhen(String value, LocalDate day) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = WHEN_PARTS.matcher(value.strip());
        if (!matcher.find()) {
            return null;
        }
        try {
            int year = matcher.group(1) == null ? day.getYear() : Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int dayOfMonth = Integer.parseInt(matcher.group(3));
            int hour = matcher.group(4) == null ? 9 : Integer.parseInt(matcher.group(4));
            int minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
            return LocalDateTime.of(year, month, dayOfMonth, hour, minute);
        } catch (RuntimeException e) {
            // 2 月 30 号这类非法日期：当没给时间，不抛
            return null;
        }
    }

    /** 把 {@code field} 截断到 0-100 整数（模型偶尔给 120 或 -5）。 */
    private void clampScore(JsonNode holder, String field) {
        if (holder == null || !holder.isObject()) {
            return;
        }
        Double value = numeric(holder.path(field));
        if (value == null) {
            return;
        }
        long clamped = Math.max(0, Math.min(100, Math.round(value)));
        ((ObjectNode) holder).put(field, (int) clamped);
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
                // when 被 normalizeDates 清空时不打印时间括号：宁可不写时间，也不给客户看一个错的日期
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
     * 拼给模型的 userInput：{当前时间 + 商机事实 + 最近 3 条跟进 + 报价概况} → 分隔语 → 【本次回复】 → 知识包。
     *
     * <p><b>第一行必须是当前时间</b>：跟进记录只落「月-日」，模型看不到年份，会把
     * {@code nextActions[].when} 猜成去年。先告诉它今天是几号，再由 {@link #normalizeDates} 兜第二道。
     *
     * <p><b>预算</b>：userInput 目标 ≤ {@link #CONTEXT_BUDGET} 字，超了就把历史从 3 条降到 2 条——
     * 输入越短首字越快（实测 45~60s），模型也越不容易把旧事实当现状。
     *
     * <p>紧凑文本块而不是 JSON：多数候选模型对键值文本的遵循度更稳，也省 token。
     */
    private String buildContext(SalesOpportunity opportunity, OpportunityCoach coach, LocalDate today) {
        LocalDateTime now = LocalDateTime.now();
        String body = background(opportunity, now, CONTEXT_FOLLOW_UPS) + currentEvent(coach);
        if (body.length() > CONTEXT_BUDGET) {
            // 还是超预算：历史砍到 2 条，再长只会拖慢生成
            body = background(opportunity, now, CONTEXT_FOLLOW_UPS_MIN) + currentEvent(coach);
        }
        return body + knowledge();
    }

    /** 背景块：当前时间 + 商机事实 + 历史跟进 + 报价概况。<b>它只是背景</b>，不是客户当下说了什么。 */
    private String background(SalesOpportunity opportunity, LocalDateTime now, int followUpCount) {
        StringBuilder sb = new StringBuilder();
        // 第一行给「今天是几号、星期几」：模型不知道今天，就只能拿训练数据里的年份凑
        sb.append("【当前时间】").append(WHEN_TEXT.format(now))
                .append('（').append(WEEK_TEXT.format(now)).append("）\n");
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
            // 备注从 300 字降到 200 字：它是人工随手写的，越长越容易把模型带进无关细节
            sb.append("备注：").append(clip(opportunity.getNote().strip(), CONTEXT_NOTE_MAX)).append('\n');
        }

        sb.append("\n【历史跟进】（仅 ").append(followUpCount)
                .append(" 条，只是背景；超过 14 天的记录不代表当前状态）\n");
        List<SalesOpportunityFollowUp> followUps = recentFollowUps(opportunity.getId(), followUpCount);
        if (followUps.isEmpty()) {
            sb.append("（暂无跟进记录：这是冷商机，先确认进展与决策链）\n");
        } else {
            for (SalesOpportunityFollowUp item : followUps) {
                // 格式：2026-10-13 14:32（28 天前）跟进人：内容——带年份又带「距今多久」，
                // 否则模型会把 9/10 个月前的旧事实（比如「预算卡法务」）当成现状写进诊断
                sb.append(item.getFollowUpAt() == null ? "时间未知" : WHEN_TEXT.format(item.getFollowUpAt()))
                        .append('（').append(sinceText(item.getFollowUpAt(), now)).append('）')
                        .append(nz(item.getFollower()))
                        .append("：")
                        .append(clip(item.getContent(), CONTEXT_FOLLOW_UP_MAX));
                if (item.getProbability() != null) {
                    sb.append("（概率 ").append(item.getProbability()).append("%）");
                }
                sb.append('\n');
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

    /**
     * 「刚刚发生的事」块：本次触发的问句与客户回复，<b>永远排在背景之后</b>。
     *
     * <p>前面那句分隔语是给模型的分界标：旧跟进与本次回复冲突时，一律以本次回复为准；
     * 没有新回复（手动生成）时也明确写一句，容不得模型自己想象。
     */
    private String currentEvent(OpportunityCoach coach) {
        StringBuilder sb = new StringBuilder("\n以上为背景，下面是刚刚发生的事\n");
        if (coach.getPromptId() == null) {
            sb.append("【本次回复】\n（本次为手动生成，客户没有新的回复；请基于上述背景给出下一步）\n");
            return sb.toString();
        }
        OpportunityFollowupPrompt prompt = promptMapper.selectById(coach.getPromptId());
        if (prompt == null) {
            sb.append("【本次回复】\n（触发的问句已不存在，按客户没有新回复处理）\n");
            return sb.toString();
        }
        // 问句 300 字、回复 500 字：再长也不会让建议更准，只会把生成拖慢
        sb.append("【引用问句】\n").append(clip(prompt.getQuestionText(), 300)).append('\n');
        sb.append("\n【本次回复】\n")
                .append(StringUtils.hasText(prompt.getReplyText())
                        ? clip(prompt.getReplyText(), 500) : "（未记录回复）")
                .append('\n');
        return sb.toString();
    }

    /**
     * 运营维护的方案/案例知识包（配置 {@code opportunity.coach.knowledge}，Markdown）。
     *
     * <p>没这块时模型只能空想产品能力与案例，追问和方案就特别笼统；配了就以内部参考放在
     * userInput 末尾，并把「不得直接泄露客户名 / 不得把内部报价口径写进客户话术」说清楚。
     *
     * <p>读不到或为空一律跳过，绝不能因为知识库没配或配错而让整次生成失败。
     */
    private String knowledge() {
        String value;
        try {
            value = systemConfigService.getValue(CONFIG_GROUP, KNOWLEDGE_KEY, null);
        } catch (Exception e) {
            log.warn("商机 AI 教练知识包读取失败，本次跳过: err={}", e.getMessage());
            return "";
        }
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return "\n【已知方案与案例（内部参考，不得直接泄露客户名）】\n"
                + clip(value, KNOWLEDGE_MAX) + '\n';
    }

    /** 「距今多久」：30 天内写天数，超过写月数，让模型一眼看出这条是旧账还是现状。 */
    private String sinceText(LocalDateTime time, LocalDateTime now) {
        if (time == null) {
            return "时间未知";
        }
        long days = ChronoUnit.DAYS.between(time.toLocalDate(), now.toLocalDate());
        if (days <= 0) {
            return "今天";
        }
        if (days <= 30) {
            return days + " 天前";
        }
        return Math.max(1, days / 30) + " 个月前";
    }

    /**
     * 最近 N 条跟进记录（新的在前）。
     *
     * <p>查询异常返回空列表：上下文缺一块也不该让生成失败。
     */
    private List<SalesOpportunityFollowUp> recentFollowUps(Long opportunityId, int count) {
        try {
            List<SalesOpportunityFollowUp> rows = followUpMapper.selectList(
                    new LambdaQueryWrapper<SalesOpportunityFollowUp>()
                            .eq(SalesOpportunityFollowUp::getOpportunityId, opportunityId)
                            .orderByDesc(SalesOpportunityFollowUp::getFollowUpAt)
                            .orderByDesc(SalesOpportunityFollowUp::getId)
                            .last("limit " + Math.max(1, count)));
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
