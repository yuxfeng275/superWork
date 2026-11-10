package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.bu.management.mapper.OpportunityCoachMapper;
import com.bu.management.mapper.OpportunityFollowupPromptMapper;
import com.bu.management.mapper.QuotationMapper;
import com.bu.management.mapper.SalesOpportunityFollowUpMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
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
    @Mock private Executor executor;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private OpportunityCoachService service;

    @BeforeEach
    void setUp() {
        service = new OpportunityCoachService(coachMapper, opportunityMapper, followUpMapper, promptMapper,
                quotationMapper, digestLlmClient, systemConfigService, objectMapper, followUpSender, executor);
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
        verify(executor, never()).execute(any());
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

    // ==================== 兜底巡检（事务竞态 / 线程池丢任务留下的 PENDING 行） ====================

    @Test
    void 巡检重跑超过两分钟仍PENDING的行() {
        OpportunityCoach stalled = coach(7L, 1L);
        stalled.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(coachMapper.selectList(any())).thenReturn(List.of(stalled));
        when(coachMapper.selectById(7L)).thenReturn(stalled);
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());
        when(digestLlmClient.chat(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("AI 模型未配置"));

        service.sweepPending();

        // 真的重跑了一遍 generate：先置 GENERATING、失败后落 FAILED
        assertThat(stalled.getStatus()).isEqualTo(OpportunityCoach.STATUS_FAILED);
        verify(coachMapper, atLeast(2)).updateById(any());
    }

    @Test
    void 巡检单行失败不影响后面的行() {
        OpportunityCoach broken = coach(8L, null);
        broken.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        OpportunityCoach healthy = coach(9L, null);
        healthy.setCreatedAt(LocalDateTime.now().minusMinutes(3));
        when(coachMapper.selectList(any())).thenReturn(List.of(broken, healthy));
        when(coachMapper.selectById(8L)).thenThrow(new IllegalStateException("数据库抖了一下"));
        when(coachMapper.selectById(9L)).thenReturn(healthy);
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());
        when(digestLlmClient.chat(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("AI 模型未配置"));

        service.sweepPending();

        // 第一行炸了，第二行照样跑到底
        assertThat(healthy.getStatus()).isEqualTo(OpportunityCoach.STATUS_FAILED);
    }

    @Test
    void 巡检碰到已经出结果或已在生成中的行不再调模型也不改状态() {
        OpportunityCoach success = coach(10L, null);
        success.setStatus(OpportunityCoach.STATUS_SUCCESS);
        OpportunityCoach generating = coach(11L, null);
        generating.setStatus(OpportunityCoach.STATUS_GENERATING);
        when(coachMapper.selectList(any())).thenReturn(List.of(success, generating));
        when(coachMapper.selectById(10L)).thenReturn(success);
        when(coachMapper.selectById(11L)).thenReturn(generating);

        service.sweepPending();

        verify(digestLlmClient, never()).chat(anyString(), anyString(), any());
        verify(coachMapper, never()).updateById(any());
    }
}
