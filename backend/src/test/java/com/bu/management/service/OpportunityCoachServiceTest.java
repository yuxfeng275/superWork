package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.OpportunityCoach;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.integration.DigestLlmClient;
import com.bu.management.mapper.AiAgentUsageMapper;
import com.bu.management.mapper.AiPromptMapper;
import com.bu.management.mapper.OpportunityCoachMapper;
import com.bu.management.mapper.OpportunityFollowupPromptMapper;
import com.bu.management.mapper.QuotationMapper;
import com.bu.management.mapper.SalesOpportunityFollowUpMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 商机 AI 教练的关键分支：模型返回不合格要换候选、没模型要落 FAILED、超额不入队、
 * 采纳要写跟进并推进下次跟进、企微摘要不得漏出金额与概率。
 *
 * <p>这几条决定「会不会瞎编给客户看」「会不会被 LLM 费用薅穿」「会不会把毛利发到企微」，
 * 所以每条都单独钉住，不依赖真实数据库与真实模型。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpportunityCoachServiceTest {

    @Mock private OpportunityCoachMapper coachMapper;
    @Mock private SalesOpportunityMapper opportunityMapper;
    @Mock private SalesOpportunityFollowUpMapper followUpMapper;
    @Mock private OpportunityFollowupPromptMapper promptMapper;
    @Mock private QuotationMapper quotationMapper;
    @Mock private DigestLlmClient digestLlmClient;
    @Mock private SystemConfigService systemConfigService;
    @Mock private OpportunityFollowUpSender followUpSender;
    /** 新基建：入队改走 ai_task，旧线程池已删除，构造参数同步换成这三个。 */
    @Mock private AiTaskService aiTaskService;
    @Mock private AiPromptMapper aiPromptMapper;
    @Mock private AiAgentUsageMapper aiAgentUsageMapper;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private OpportunityCoachService service;

    @BeforeEach
    void setUp() {
        // 构造参数变化：@Qualifier("opportunityTaskExecutor") Executor → AiTaskService + PromptRegistry + AiCallRecorder。
        // AiCallRecorder / PromptRegistry 用真实现包住 mock：前者转调 mock 的 DigestLlmClient（原有 chat 打桩与断言口径不变），
        // 后者查不到 ai_prompt 就退回代码常量（提示词断言与原来一致）。
        AiCallRecorder aiCallRecorder = new AiCallRecorder(digestLlmClient, aiAgentUsageMapper);
        PromptRegistry promptRegistry = new PromptRegistry(aiPromptMapper, 60);
        service = new OpportunityCoachService(coachMapper, opportunityMapper, followUpMapper, promptMapper,
                quotationMapper, digestLlmClient, systemConfigService, objectMapper, followUpSender,
                aiTaskService, promptRegistry, aiCallRecorder);
        // 开关默认开、每日上限默认 10（读不到也按这套口径走）
        when(systemConfigService.getBoolean("opportunity", "coach.enabled", true)).thenReturn(true);
        when(systemConfigService.getBoolean("opportunity", "coach.wecom-push", true)).thenReturn(true);
        when(systemConfigService.getValue("opportunity", "coach.daily-limit", "10")).thenReturn("10");
        when(quotationMapper.selectList(any())).thenReturn(List.of());
        when(followUpMapper.selectList(any())).thenReturn(List.of());
    }

    private SalesOpportunity opportunity() {
        SalesOpportunity opp = new SalesOpportunity();
        opp.setId(11L);
        opp.setName("华东区智能制造改造项目");
        opp.setCustomer("帝亚吉欧");
        opp.setStatus("需求确认");
        opp.setProbability(40);
        opp.setAmount(new BigDecimal("1234567"));
        opp.setOwner("张三");
        opp.setOwnerUserId(7L);
        opp.setBusinessLine("电商业务BU");
        return opp;
    }

    private OpportunityCoach coach(Long id, Long promptId) {
        OpportunityCoach coach = new OpportunityCoach();
        coach.setId(id);
        coach.setOpportunityId(11L);
        coach.setPromptId(promptId);
        coach.setTriggerSource(OpportunityCoach.SOURCE_MANUAL);
        coach.setStatus(OpportunityCoach.STATUS_PENDING);
        coach.setCreatedAt(LocalDateTime.now());
        return coach;
    }

    /** 取最后一次回写的行（generate 会先写 GENERATING、再写最终的 SUCCESS/FAILED）。 */
    private OpportunityCoach lastUpdate() {
        ArgumentCaptor<OpportunityCoach> captor = ArgumentCaptor.forClass(OpportunityCoach.class);
        verify(coachMapper, atLeast(2)).updateById(captor.capture());
        List<OpportunityCoach> values = captor.getAllValues();
        return values.get(values.size() - 1);
    }

    // ==================== strictCheck ====================

    @Test
    void 缺questions或nextActions时严格校验抛异常让客户端换候选() throws Exception {
        AtomicReference<Consumer<JsonNode>> captured = new AtomicReference<>();
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(2));
            return new DigestLlmClient.ChatResult(objectMapper.readTree(
                    "{\"readiness\":{\"score\":50},\"questions\":[{\"question\":\"预算谁定？\"}],"
                            + "\"solutions\":[{\"title\":\"先聊决策链\"}],"
                            + "\"nextActions\":[{\"action\":\"约陈总\"}]}"),
                    "openai", "gpt-4o-mini");
        });
        when(coachMapper.selectById(1L)).thenReturn(coach(1L, null));
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());

        service.generate(1L);

        Consumer<JsonNode> check = captured.get();
        assertThat(check).isNotNull();
        assertThatThrownBy(() -> check.accept(objectMapper.readTree("{\"readiness\":{\"score\":50}}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("questions");
        assertThatThrownBy(() -> check.accept(objectMapper.readTree(
                "{\"readiness\":{\"score\":50},\"questions\":[{\"question\":\"预算谁定？\"}],"
                        + "\"solutions\":[{\"title\":\"先聊决策链\"}]}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nextActions");
        // 骨架齐全的包必须放行，否则好模型也会被换掉
        check.accept(objectMapper.readTree(
                "{\"readiness\":{\"score\":50},\"questions\":[{\"question\":\"预算谁定？\"}],"
                        + "\"solutions\":[{\"title\":\"先聊决策链\"}],"
                        + "\"nextActions\":[{\"action\":\"约陈总\"}]}"));
        assertThat(lastUpdate().getStatus()).isEqualTo(OpportunityCoach.STATUS_SUCCESS);
    }

    // ==================== 生成失败 ====================

    @Test
    void 没有可用摘要模型时置FAILED且原因提到模型() {
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenThrow(new IllegalStateException(
                "AI 模型未配置或未启用，请在「模型管理」勾选「摘要使用」的模型并补全接口地址"));
        when(coachMapper.selectById(2L)).thenReturn(coach(2L, null));
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());

        service.generate(2L);

        OpportunityCoach failed = lastUpdate();
        assertThat(failed.getStatus()).isEqualTo(OpportunityCoach.STATUS_FAILED);
        assertThat(failed.getError()).contains("模型");
        assertThat(failed.getModel()).isNull();
    }

    // ==================== 配额 ====================

    @Test
    void 当日配额用尽时enqueue返回null且不落行() {
        when(coachMapper.selectCount(any())).thenReturn(10L);

        OpportunityCoach row = service.enqueue(11L, null, OpportunityCoach.SOURCE_MANUAL, 7L);

        assertThat(row).isNull();
        verify(coachMapper, never()).insert(any());
        // 超额时一条 ai_task 也不得落（旧断言是「不丢线程池」）
        verify(aiTaskService, never()).enqueue(anyString(), any(), anyString());
    }

    @Test
    void 开关关闭时enqueue返回null且不落行() {
        when(systemConfigService.getBoolean("opportunity", "coach.enabled", true)).thenReturn(false);

        assertThat(service.enqueue(11L, null, OpportunityCoach.SOURCE_INBOUND, 7L)).isNull();
        verify(coachMapper, never()).insert(any());
    }

    // ==================== 采纳 ====================

    @Test
    void 采纳建议写AI_COACH来源跟进并推进商机下次跟进() {
        OpportunityCoach row = coach(3L, null);
        row.setContentJson("{\"nextActions\":[{\"action\":\"发方案给陈总\",\"when\":\"2026-12-03 10:30\"}]}");
        when(coachMapper.selectById(3L)).thenReturn(row);
        SalesOpportunity opp = opportunity();
        when(opportunityMapper.selectById(11L)).thenReturn(opp);

        SalesOpportunityFollowUp item = service.adopt(3L, 0, 7L);

        ArgumentCaptor<SalesOpportunityFollowUp> captor =
                ArgumentCaptor.forClass(SalesOpportunityFollowUp.class);
        verify(followUpMapper).insert(captor.capture());
        SalesOpportunityFollowUp written = captor.getValue();
        assertThat(written.getContent()).isEqualTo("AI 教练建议：发方案给陈总");
        assertThat(written.getSource()).isEqualTo(OpportunityFollowUpService.SOURCE_AI_COACH);
        assertThat(written.getPromptId()).isNull();
        assertThat(written.getStatus()).isEqualTo("需求确认");
        assertThat(written.getProbability()).isEqualTo(40);
        assertThat(item.getOpportunityId()).isEqualTo(11L);

        verify(opportunityMapper).updateById(opp);
        assertThat(opp.getNextFollowUpAt()).isEqualTo(LocalDateTime.of(2026, 12, 3, 10, 30));
        assertThat(opp.getFollowUpState()).isEqualTo("PENDING");
        assertThat(opp.getLastFollowUpAt()).isNotNull();
        // 阶段永远由人工改，采纳建议不推进
        assertThat(opp.getStatus()).isEqualTo("需求确认");
        assertThat(opp.getProbability()).isEqualTo(40);
    }

    @Test
    void 采纳越界下标抛参数异常不写库() {
        OpportunityCoach row = coach(4L, null);
        row.setContentJson("{\"nextActions\":[{\"action\":\"发方案\"}]}");
        when(coachMapper.selectById(4L)).thenReturn(row);

        assertThatThrownBy(() -> service.adopt(4L, 3, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(followUpMapper, never()).insert(any());
    }

    // ==================== 企微外发边界 ====================

    @Test
    void 企微摘要不含金额毛利与概率数字且只带三条追问() {
        OpportunityCoach row = coach(5L, 1L);
        row.setContentJson("{\"readiness\":{\"score\":38},"
                + "\"diagnosis\":\"客户对金额敏感，毛利约 35%，成交概率 60%，决策链还没打通\","
                + "\"risks\":[\"单点联系人\"],"
                + "\"questions\":["
                + "{\"question\":\"预算 120 万走哪个口子？\"},"
                + "{\"question\":\"决策链上还有谁？\"},"
                + "{\"question\":\"时间窗能落在本月吗？\"},"
                + "{\"question\":\"竞品聊过哪家？\"}],"
                + "\"solutions\":[{\"title\":\"拉高层\",\"pitch\":\"用 80 万的首年投入换试点\"}],"
                + "\"nextActions\":[{\"action\":\"补一版 80 万的报价\",\"when\":\"2026-12-03 10:30\"}],"
                + "\"probabilitySuggestion\":{\"value\":60,\"reason\":\"已过技术评审\"}}");

        String markdown = service.buildWecomMarkdown(row);

        assertThat(markdown).contains("详细建议见合拍商机详情");
        assertThat(markdown).contains("下一步：").contains("2026-12-03 10:30");
        // 金额、毛利、概率数字一概不出去（企微可能被截屏转发给客户）
        assertThat(markdown).doesNotContain("120").doesNotContain("80 万")
                .doesNotContain("35%").doesNotContain("60%").doesNotContain("%");
        assertThat(markdown.lines().filter(line -> line.startsWith("问：")).count()).isEqualTo(3);
        assertThat(markdown.length()).isLessThanOrEqualTo(500);
    }

    @Test
    void 内容为空时摘要只留引导语() {
        OpportunityCoach row = coach(6L, null);
        row.setContentJson(null);

        String markdown = service.buildWecomMarkdown(row);

        assertThat(markdown).contains("详细建议见合拍商机详情");
        assertThat(markdown.length()).isLessThanOrEqualTo(500);
    }

    // ==================== 任务处理器（原兜底巡检已交给 AiTaskService 的调度与重试） ====================

    private OpportunityCoachService.CoachTaskHandler handler() {
        return new OpportunityCoachService.CoachTaskHandler(service);
    }

    @Test
    void 任务处理器按payload重跑PENDING行() throws Exception {
        OpportunityCoach stalled = coach(7L, 1L);
        stalled.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(coachMapper.selectById(7L)).thenReturn(stalled);
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());
        when(digestLlmClient.chat(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("AI 模型未配置"));

        String result = handler().handle("{\"coachId\":7}");

        // handler 不向上抛（生成失败已回写到业务行），成功返回 null
        assertThat(result).isNull();
        // 真的重跑了一遍 generate：先置 GENERATING、失败后落 FAILED
        assertThat(stalled.getStatus()).isEqualTo(OpportunityCoach.STATUS_FAILED);
        verify(coachMapper, atLeast(2)).updateById(any());
    }

    @Test
    void 任务处理器payload缺coachId时抛异常且不调模型() {
        // 这种任务永远跑不通，抛异常让调度器重试到 FAILED 并留下原因
        assertThatThrownBy(() -> handler().handle("{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coachId");
        verify(digestLlmClient, never()).chat(anyString(), anyString(), any());
    }

    @Test
    void 任务处理器碰到已经出结果的行不再调模型也不改状态() throws Exception {
        OpportunityCoach success = coach(10L, null);
        success.setStatus(OpportunityCoach.STATUS_SUCCESS);
        when(coachMapper.selectById(10L)).thenReturn(success);

        handler().handle("{\"coachId\":10}");

        verify(digestLlmClient, never()).chat(anyString(), anyString(), any());
        verify(coachMapper, never()).updateById(any());
    }

    // ==================== 落库前的日期纠正（防幻觉：模型爱把年份写成去年） ====================

    /**
     * 跑一次 generate，返回落库的 content_json。模型只回一条 nextActions，方便断言。
     *
     * @param modelWhen 模型给出的 when 原文（故意写成非法/过去的日期）
     * @param coachId   教练行 ID
     */
    private String generateAndCaptureContent(String modelWhen, Long coachId) throws Exception {
        String json = "{\"readiness\":{\"score\":50},\"questions\":[{\"question\":\"预算谁定？\"}],"
                + "\"solutions\":[{\"title\":\"先聊决策链\"}],"
                + "\"nextActions\":[{\"action\":\"约陈总\",\"when\":\"" + modelWhen + "\"}]}";
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenReturn(
                new DigestLlmClient.ChatResult(objectMapper.readTree(json), "openai", "gpt-4o-mini"));
        when(coachMapper.selectById(coachId)).thenReturn(coach(coachId, null));
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());

        service.generate(coachId);

        String content = lastUpdate().getContentJson();
        assertThat(content).isNotNull();
        return content;
    }

    @Test
    void 模型把when写成去年时落库前被拉回今年() throws Exception {
        int year = LocalDate.now().getYear();
        LocalDate target = LocalDate.now().plusDays(10);
        // 恰好跨年那几天，「拉回今年」会把目标日期打到过去，这条用例在那种窗口没有意义
        assumeTrue(target.getYear() == year);
        String monthDay = String.format("%02d-%02d", target.getMonthValue(), target.getDayOfMonth());

        // 实测模型给的就是去年：2025-11-12 10:00（今年 2026）
        String content = generateAndCaptureContent((year - 1) + "-" + monthDay + " 10:00", 20L);

        JsonNode when = objectMapper.readTree(content).path("nextActions").get(0).path("when");
        assertThat(when.asText()).isEqualTo(year + "-" + monthDay + " 10:00");
    }

    @Test
    void 模型把when写成过去年时清空并在动作末尾标时间待定() throws Exception {
        // 元旦当天「早于今天 00:00」不成立，跳过
        assumeTrue(LocalDate.now().getDayOfYear() > 1);

        // 2020 被拉回今年后仍然早于今天，只能空着，绝不能把错时间发给客户
        String content = generateAndCaptureContent("2020-01-01 10:00", 21L);

        JsonNode action = objectMapper.readTree(content).path("nextActions").get(0);
        assertThat(action.path("when").asText()).isEmpty();
        assertThat(action.path("action").asText()).isEqualTo("约陈总" + OpportunityCoachService.WHEN_TBD);
    }

    // ==================== 落库前的 owner 白名单与条数上限（防幻觉：模型爱编人名） ====================

    /**
     * 跑一次 generate，商机负责人固定为 {@code opportunityOwner}，返回落库的 content_json。
     *
     * @param opportunityOwner 商机负责人姓名（写进 owner 白名单）
     * @param questions        questions 数组原文
     * @param solutions        solutions 数组原文
     * @param nextActions      nextActions 数组原文
     * @param coachId          教练行 ID
     */
    private String generateAndCapture(String opportunityOwner, String questions, String solutions,
            String nextActions, Long coachId) throws Exception {
        String json = "{\"readiness\":{\"score\":50},\"questions\":" + questions
                + ",\"solutions\":" + solutions
                + ",\"nextActions\":" + nextActions + "}";
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenReturn(
                new DigestLlmClient.ChatResult(objectMapper.readTree(json), "openai", "gpt-4o-mini"));
        when(coachMapper.selectById(coachId)).thenReturn(coach(coachId, null));
        SalesOpportunity opp = opportunity();
        opp.setOwner(opportunityOwner);
        when(opportunityMapper.selectById(11L)).thenReturn(opp);

        service.generate(coachId);

        String content = lastUpdate().getContentJson();
        assertThat(content).isNotNull();
        return content;
    }

    private static final String ONE_QUESTION = "[{\"question\":\"预算谁定？\"}]";
    private static final String ONE_SOLUTION = "[{\"title\":\"先聊决策链\"}]";

    @Test
    void 模型编造的人名owner落库前被改成销售() throws Exception {
        // 实测模型给的是「王赢」，而上下文里根本没有这个人（负责人是「王瀛」）——直接按幻觉处理
        String content = generateAndCapture("王瀛", ONE_QUESTION, ONE_SOLUTION,
                "[{\"action\":\"发方案\",\"when\":\"\",\"owner\":\"王赢\"}]", 40L);

        JsonNode action = objectMapper.readTree(content).path("nextActions").get(0);
        assertThat(action.path("owner").asText()).isEqualTo(OpportunityCoachService.OWNER_SALES);
    }

    @Test
    void owner为客户或负责人本人时保持不变() throws Exception {
        String content = generateAndCapture("王瀛", ONE_QUESTION, ONE_SOLUTION,
                "[{\"action\":\"约复盘\",\"when\":\"\",\"owner\":\"客户\"},"
                        + "{\"action\":\"梳理决策链\",\"when\":\"\",\"owner\":\"王瀛\"}]", 41L);

        JsonNode actions = objectMapper.readTree(content).path("nextActions");
        assertThat(actions.get(0).path("owner").asText()).isEqualTo(OpportunityCoachService.OWNER_CUSTOMER);
        assertThat(actions.get(1).path("owner").asText()).isEqualTo("王瀛");
    }

    @Test
    void 追问方案下一步超过条数上限时落库前被截断() throws Exception {
        String content = generateAndCapture("王瀛",
                "[{\"question\":\"q1\"},{\"question\":\"q2\"},{\"question\":\"q3\"},{\"question\":\"q4\"}]",
                "[{\"title\":\"s1\"},{\"title\":\"s2\"},{\"title\":\"s3\"}]",
                "[{\"action\":\"a1\",\"when\":\"\",\"owner\":\"销售\"},"
                        + "{\"action\":\"a2\",\"when\":\"\",\"owner\":\"销售\"},"
                        + "{\"action\":\"a3\",\"when\":\"\",\"owner\":\"销售\"},"
                        + "{\"action\":\"a4\",\"when\":\"\",\"owner\":\"销售\"}]", 42L);

        JsonNode root = objectMapper.readTree(content);
        // 条数越多输出越长、首字越慢，第 4 条基本都是凑数的
        assertThat(root.path("questions").size()).isEqualTo(3);
        assertThat(root.path("solutions").size()).isEqualTo(2);
        assertThat(root.path("nextActions").size()).isEqualTo(3);
    }

    // ==================== 上下文：当前时间 / 可配知识包 ====================

    /** 跑一次 generate 并截下传给模型的 userInput（第 2 个参数）。 */
    private String captureUserInput(Long coachId) {
        AtomicReference<String> captured = new AtomicReference<>();
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return new DigestLlmClient.ChatResult(objectMapper.readTree(
                    "{\"readiness\":{\"score\":50},\"questions\":[{\"question\":\"预算谁定？\"}],"
                            + "\"solutions\":[{\"title\":\"先聊决策链\"}],"
                            + "\"nextActions\":[{\"action\":\"约陈总\",\"when\":\"\"}]}"),
                    "openai", "gpt-4o-mini");
        });
        when(coachMapper.selectById(coachId)).thenReturn(coach(coachId, null));
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());

        service.generate(coachId);

        assertThat(captured.get()).isNotNull();
        return captured.get();
    }

    @Test
    void 上下文第一行是带星期几的今天() {
        String userInput = captureUserInput(30L);

        String firstLine = userInput.lines().findFirst().orElse("");
        // 模型不知道今天是几号时就只能猜年份，所以这一行必须在最前面
        assertThat(firstLine).startsWith("【当前时间】").contains(LocalDate.now().toString());
    }

    @Test
    void 配了知识包时userInput末尾带上它没配时不带() {
        // 没配：静默跳过，不报错也不留空标题
        assertThat(captureUserInput(31L)).doesNotContain("已知方案与案例");

        when(systemConfigService.getValue("opportunity", "coach.knowledge", null))
                .thenReturn("## 电商中台标准方案\n- 支持全渠道库存一盘棋\n- 双 11 大促峰值已验证");

        String userInput = captureUserInput(32L);
        assertThat(userInput).contains("【已知方案与案例（内部参考，不得直接泄露客户名）】")
                .contains("支持全渠道库存一盘棋");
        // 知识包必须排在最后：前面的背景与本次回复才是判断依据
        assertThat(userInput.trim()).endsWith("双 11 大促峰值已验证");
    }
}
