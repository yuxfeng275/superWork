package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

/**
 * 商机跟进闭环的关键分支：幂等落问句、回复只认最新一条、阶段守卫、企微降级、静音。
 *
 * <p>这些分支决定「会不会重复问客户」「会不会绕过报价单前置条件」「企微炸了会不会丢数据」，
 * 所以每个都单独钉住，不依赖真实数据库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpportunityFollowUpServiceTest {

    @Mock private SalesOpportunityMapper opportunityMapper;
    @Mock private SalesOpportunityFollowUpMapper followUpMapper;
    @Mock private OpportunityFollowupPromptMapper promptMapper;
    @Mock private QuotationMapper quotationMapper;
    @Mock private EmailWeComMappingMapper weComMappingMapper;
    @Mock private UserMapper userMapper;
    @Mock private WeComClient weComClient;
    @Mock private WeComCliService weComCliService;
    @Mock private SystemConfigService systemConfigService;

    private OpportunityFollowUpService service;

    @BeforeEach
    void setUp() {
        service = new OpportunityFollowUpService(opportunityMapper, followUpMapper, promptMapper,
                quotationMapper, weComMappingMapper, userMapper, weComClient,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        when(promptMapper.selectOne(any())).thenReturn(null);
        when(quotationMapper.selectList(any())).thenReturn(List.of());
        when(weComMappingMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectById(any())).thenReturn(null);
    }

    private SalesOpportunity opportunity() {
        SalesOpportunity opp = new SalesOpportunity();
        opp.setId(11L);
        opp.setName("华东区智能制造改造项目一二三");
        opp.setStatus("需求确认");
        opp.setProbability(40);
        opp.setAmount(new BigDecimal("1234567"));
        opp.setOwner("张三");
        opp.setOwnerUserId(7L);
        opp.setRemindMuted(0);
        return opp;
    }

    // ==================== createPrompt ====================

    @Test
    void 首次落问句用站内兜底渠道且问句不含金额() {
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());

        OpportunityFollowupPrompt prompt = service.createPrompt(11L, "今日跟进", null);

        assertThat(prompt.getChannel()).isEqualTo(OpportunityFollowUpService.CHANNEL_INAPP);
        assertThat(prompt.getState()).isEqualTo(OpportunityFollowupPrompt.STATE_SENT);
        assertThat(prompt.getTargetUserId()).isEqualTo(7L);
        assertThat(prompt.getDedupeKey())
                .isEqualTo("opp:11:今日跟进:" + LocalDate.now());
        assertThat(prompt.getQuestionText()).doesNotContain("1234567").doesNotContain("123.46");
        assertThat(prompt.getQuestionText().split("\n")).hasSize(4);
        assertThat(prompt.getQuestionText()).contains("华东区智能制造改造项目");
        assertThat(prompt.getQuestionText()).doesNotContain("华东区智能制造改造项目一二三");
        verify(promptMapper).insert(prompt);
    }

    @Test
    void 幂等键已存在时直接返回已有问句不重复插入() {
        OpportunityFollowupPrompt existing = new OpportunityFollowupPrompt();
        existing.setId(99L);
        existing.setState(OpportunityFollowupPrompt.STATE_SENT);
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());
        when(promptMapper.selectOne(any())).thenReturn(existing);

        OpportunityFollowupPrompt prompt = service.createPrompt(11L, "今日跟进", "WECOM_APP");

        assertThat(prompt).isSameAs(existing);
        verify(promptMapper, never()).insert(any());
    }

    @Test
    void 并发撞唯一键时返回已落库的那条而不是抛错() {
        OpportunityFollowupPrompt concurrent = new OpportunityFollowupPrompt();
        concurrent.setId(100L);
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());
        when(promptMapper.selectOne(any())).thenReturn(null, concurrent);
        when(promptMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_opp_prompt_dedupe"));

        OpportunityFollowupPrompt prompt = service.createPrompt(11L, "今日跟进", null);

        assertThat(prompt).isSameAs(concurrent);
    }

    @Test
    void 商机不存在时落问句抛业务异常() {
        when(opportunityMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.createPrompt(404L, "今日跟进", null))
                .hasMessageContaining("商机不存在");
    }

    // ==================== applyReply ====================

    @Test
    void 回复解析出结构化结果时问句转已解析并记录回复() {
        OpportunityFollowupPrompt sent = new OpportunityFollowupPrompt();
        sent.setId(5L);
        sent.setState(OpportunityFollowupPrompt.STATE_SENT);
        sent.setOpportunityId(11L);
        sent.setSentAt(LocalDateTime.now().minusHours(1));
        when(promptMapper.selectOne(any())).thenReturn(sent);

        OpportunityFollowupPrompt updated = service.applyReply("wxid_zhangsan", 11L,
                "已经进入商务谈判，概率 70%，下周三 15:00 再谈价格", "msg-1", "WECOM_APP");

        assertThat(updated.getState()).isEqualTo(OpportunityFollowupPrompt.STATE_PARSED);
        assertThat(updated.getReplyText()).contains("商务谈判");
        assertThat(updated.getParsedJson()).contains("商务谈判");
        assertThat(updated.getRepliedAt()).isNotNull();
        assertThat(updated.getWecomUserid()).isEqualTo("wxid_zhangsan");
        verify(promptMapper).updateById(sent);
    }

    @Test
    void 回复没解析出结构时只标已回复() {
        OpportunityFollowupPrompt sent = new OpportunityFollowupPrompt();
        sent.setId(5L);
        sent.setState(OpportunityFollowupPrompt.STATE_SENT);
        when(promptMapper.selectOne(any())).thenReturn(sent);

        OpportunityFollowupPrompt updated = service.applyReply(null, 11L, "好的收到，我再想想", "msg-2", "INAPP");

        assertThat(updated.getState()).isEqualTo(OpportunityFollowupPrompt.STATE_REPLIED);
        assertThat(updated.getRepliedAt()).isNotNull();
        assertThat(updated.getWecomUserid()).isNull();
    }

    @Test
    void 没有二十四小时内的待回复问句时重复投递不写库() {
        OpportunityFollowupPrompt updated = service.applyReply("wxid", 11L, "已成交，概率 100%", "msg-3", "WECOM_APP");

        assertThat(updated).isNull();
        verify(promptMapper, never()).updateById(any());
    }

    @Test
    void 空商机ID或空正文直接忽略() {
        assertThat(service.applyReply("wxid", null, "随便说说", "msg-4", "WECOM_APP")).isNull();
        assertThat(service.applyReply("wxid", 11L, "   ", "msg-5", "WECOM_APP")).isNull();
        verify(promptMapper, never()).updateById(any());
    }

    // ==================== confirmDraft ====================

    @Test
    void 确认时进入商务谈判没有已批报价单必须抛业务异常() {
        OpportunityFollowupPrompt prompt = new OpportunityFollowupPrompt();
        prompt.setId(6L);
        prompt.setOpportunityId(11L);
        prompt.setState(OpportunityFollowupPrompt.STATE_PARSED);
        prompt.setReplyText("已经进入商务谈判，概率 70%");
        prompt.setTargetUserId(7L);
        when(promptMapper.selectById(6L)).thenReturn(prompt);
        when(opportunityMapper.selectById(11L)).thenReturn(opportunity());

        assertThatThrownBy(() -> service.confirmDraft(6L, 7L))
                .hasMessageContaining("需先出一张已审批的报价单");
        verify(followUpMapper, never()).insert(any());
        verify(opportunityMapper, never()).updateById(any());
    }

    @Test
    void 确认时写入企微来源跟进记录并刷新商机快照() {
        OpportunityFollowupPrompt prompt = new OpportunityFollowupPrompt();
        prompt.setId(6L);
        prompt.setOpportunityId(11L);
        prompt.setState(OpportunityFollowupPrompt.STATE_PARSED);
        prompt.setReplyText("概率 60%，下周三 15:00 再沟通");
        prompt.setTargetUserId(7L);
        prompt.setRepliedAt(LocalDateTime.now().minusHours(2));
        when(promptMapper.selectById(6L)).thenReturn(prompt);
        SalesOpportunity opp = opportunity();
        when(opportunityMapper.selectById(11L)).thenReturn(opp);
        User operator = new User();
        operator.setId(7L);
        operator.setRealName("张三");
        when(userMapper.selectById(7L)).thenReturn(operator);

        SalesOpportunityFollowUp item = service.confirmDraft(6L, 7L);

        assertThat(item.getSource()).isEqualTo(OpportunityFollowUpService.SOURCE_WECOM_BOT);
        assertThat(item.getPromptId()).isEqualTo(6L);
        assertThat(item.getProbability()).isEqualTo(60);
        assertThat(item.getStatus()).isEqualTo("需求确认");
        assertThat(item.getFollower()).isEqualTo("张三");
        assertThat(item.getNextFollowUp()).isNotBlank();
        assertThat(opp.getProbability()).isEqualTo(60);
        assertThat(opp.getNextFollowUpAt()).isNotNull();
        assertThat(opp.getFollowUpState()).isEqualTo("PENDING");
        assertThat(opp.getLastFollowUpAt()).isNotNull();
        assertThat(prompt.getState()).isEqualTo(OpportunityFollowupPrompt.STATE_CONFIRMED);
        verify(followUpMapper).insert(any(SalesOpportunityFollowUp.class));
        verify(opportunityMapper).updateById(opp);
        verify(promptMapper).updateById(prompt);
    }

    @Test
    void 重复确认同一问句返回已有跟进记录不重复写() {
        OpportunityFollowupPrompt prompt = new OpportunityFollowupPrompt();
        prompt.setId(6L);
        prompt.setOpportunityId(11L);
        prompt.setState(OpportunityFollowupPrompt.STATE_CONFIRMED);
        prompt.setReplyText("概率 60%");
        when(promptMapper.selectById(6L)).thenReturn(prompt);
        SalesOpportunityFollowUp existing = new SalesOpportunityFollowUp();
        existing.setId(88L);
        when(followUpMapper.selectOne(any())).thenReturn(existing);

        SalesOpportunityFollowUp item = service.confirmDraft(6L, 7L);

        assertThat(item).isSameAs(existing);
        verify(followUpMapper, never()).insert(any());
        verify(opportunityMapper, never()).updateById(any());
    }

    @Test
    void 没有回复内容的问句不能确认() {
        OpportunityFollowupPrompt prompt = new OpportunityFollowupPrompt();
        prompt.setId(6L);
        prompt.setState(OpportunityFollowupPrompt.STATE_SENT);
        when(promptMapper.selectById(6L)).thenReturn(prompt);

        assertThatThrownBy(() -> service.confirmDraft(6L, 7L))
                .hasMessageContaining("还没有回复内容");
        verify(followUpMapper, never()).insert(any());
    }

    @Test
    void 问句不存在时确认抛业务异常() {
        when(promptMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.confirmDraft(404L, 7L))
                .hasMessageContaining("问句不存在");
    }

    // ==================== tryPush ====================

    @Test
    void 有已启用企微映射时推送成功并改渠道() {
        EmailWeComMapping mapping = new EmailWeComMapping();
        mapping.setOwnerUserId(7L);
        mapping.setWecomUserId("wxid_zhangsan");
        mapping.setEnabled(1);
        when(weComMappingMapper.selectList(any())).thenReturn(List.of(mapping));
        OpportunityFollowupPrompt prompt = new OpportunityFollowupPrompt();
        prompt.setId(6L);
        prompt.setQuestionText("【今日跟进】商机「华东」现在什么进展？");

        String channel = service.tryPush(prompt, 7L);

        assertThat(channel).isEqualTo(OpportunityFollowUpService.CHANNEL_WECOM_APP);
        assertThat(prompt.getChannel()).isEqualTo(OpportunityFollowUpService.CHANNEL_WECOM_APP);
        assertThat(prompt.getWecomUserid()).isEqualTo("wxid_zhangsan");
        verify(weComClient).pushText("wxid_zhangsan", prompt.getQuestionText());
        verify(promptMapper).updateById(prompt);
    }

    @Test
    void 企微推送抛异常时降级为站内且不向上抛() {
        EmailWeComMapping mapping = new EmailWeComMapping();
        mapping.setOwnerUserId(7L);
        mapping.setWecomUserId("wxid_zhangsan");
        mapping.setEnabled(1);
        when(weComMappingMapper.selectList(any())).thenReturn(List.of(mapping));
        org.mockito.Mockito.doThrow(new RuntimeException("企微 500"))
                .when(weComClient).pushText(anyString(), anyString());
        OpportunityFollowupPrompt prompt = new OpportunityFollowupPrompt();
        prompt.setId(6L);
        prompt.setQuestionText("问题");

        assertThat(service.tryPush(prompt, 7L)).isEqualTo(OpportunityFollowUpService.CHANNEL_INAPP);
        assertThat(prompt.getChannel()).isNotEqualTo(OpportunityFollowUpService.CHANNEL_WECOM_APP);
        verify(promptMapper, never()).updateById(any());
    }

    @Test
    void 没有企微映射或负责人为空时不推送() {
        OpportunityFollowupPrompt prompt = new OpportunityFollowupPrompt();
        prompt.setId(6L);

        assertThat(service.tryPush(prompt, 7L)).isEqualTo(OpportunityFollowUpService.CHANNEL_INAPP);
        assertThat(service.tryPush(prompt, null)).isEqualTo(OpportunityFollowUpService.CHANNEL_INAPP);
        verify(weComClient, never()).pushText(anyString(), anyString());
    }

    @Test
    void 映射未启用时不推送() {
        EmailWeComMapping mapping = new EmailWeComMapping();
        mapping.setOwnerUserId(7L);
        mapping.setWecomUserId("wxid_zhangsan");
        mapping.setEnabled(0);
        when(weComMappingMapper.selectList(any())).thenReturn(List.of(mapping));
        OpportunityFollowupPrompt prompt = new OpportunityFollowupPrompt();
        prompt.setId(6L);

        assertThat(service.tryPush(prompt, 7L)).isEqualTo(OpportunityFollowUpService.CHANNEL_INAPP);
        verify(weComClient, never()).pushText(anyString(), anyString());
    }

    // ==================== 试点发送链（OpportunityFollowUpSender） ====================

    /** 试点发送器：与生产一致地用同一批 mock；pilotOwnerUserId 用测试值 25（不依赖库里的真实账号）。 */
    private OpportunityFollowUpSender sender() {
        OpportunityFollowUpSender sender = new OpportunityFollowUpSender(weComCliService, weComClient,
                weComMappingMapper, promptMapper, systemConfigService);
        sender.pilotOwnerUserId = 25L;
        return sender;
    }

    private OpportunityFollowupPrompt prompt() {
        OpportunityFollowupPrompt prompt = new OpportunityFollowupPrompt();
        prompt.setId(6L);
        prompt.setQuestionText("【今日跟进】商机「华东」现在什么进展？");
        return prompt;
    }

    @Test
    void 试点userid存在时走机器人单聊且渠道为WECOM_BOT() {
        when(systemConfigService.getValue("opportunity", "followup.pilot-wecom-userid", null))
                .thenReturn("yufeng");
        OpportunityFollowupPrompt prompt = prompt();

        String channel = sender().send(prompt);

        assertThat(channel).isEqualTo(OpportunityFollowUpService.CHANNEL_WECOM_BOT);
        assertThat(prompt.getChannel()).isEqualTo(OpportunityFollowUpService.CHANNEL_WECOM_BOT);
        assertThat(prompt.getWecomUserid()).isEqualTo("yufeng");
        verify(weComCliService).sendMessage("yufeng", prompt.getQuestionText());
        verify(weComClient, never()).pushText(anyString(), anyString());
        verify(promptMapper).updateById(prompt);
    }

    @Test
    void 机器人抛异常时回退企微应用消息() {
        when(systemConfigService.getValue("opportunity", "followup.pilot-wecom-userid", null))
                .thenReturn("yufeng");
        org.mockito.Mockito.doThrow(new IllegalStateException("机器人通道未授权"))
                .when(weComCliService).sendMessage(anyString(), anyString());
        EmailWeComMapping pilotMapping = new EmailWeComMapping();
        pilotMapping.setOwnerUserId(25L);
        pilotMapping.setWecomUserId("wxid_yufeng");
        pilotMapping.setEnabled(1);
        when(weComMappingMapper.selectList(any())).thenReturn(List.of(pilotMapping));
        OpportunityFollowupPrompt prompt = prompt();

        String channel = sender().send(prompt);

        assertThat(channel).isEqualTo(OpportunityFollowUpService.CHANNEL_WECOM_APP);
        assertThat(prompt.getChannel()).isEqualTo(OpportunityFollowUpService.CHANNEL_WECOM_APP);
        assertThat(prompt.getWecomUserid()).isEqualTo("wxid_yufeng");
        verify(weComClient).pushText("wxid_yufeng", prompt.getQuestionText());
    }

    @Test
    void 试点目标与企微映射都没有时落站内且不抛() {
        // setUp 已让 mappings 返回空、systemConfigService.getValue 返回 null
        OpportunityFollowupPrompt prompt = prompt();

        String channel = sender().send(prompt);

        assertThat(channel).isEqualTo(OpportunityFollowUpService.CHANNEL_INAPP);
        assertThat(prompt.getChannel()).isNull();
        assertThat(prompt.getWecomUserid()).isNull();
        verify(weComCliService, never()).sendMessage(anyString(), anyString());
        verify(weComClient, never()).pushText(anyString(), anyString());
        verify(promptMapper, never()).updateById(any());
    }

    // ==================== 其它 ====================

    @Test
    void 静音与恢复只改提醒开关() {
        SalesOpportunity opp = opportunity();
        when(opportunityMapper.selectById(11L)).thenReturn(opp);

        service.mute(11L, true);
        assertThat(opp.getRemindMuted()).isEqualTo(1);

        service.mute(11L, false);
        assertThat(opp.getRemindMuted()).isEqualTo(0);
        verify(opportunityMapper, org.mockito.Mockito.times(2)).updateById(opp);
    }

    @Test
    void 报价已发出不能把阶段拉回初步接触() {
        SalesOpportunity opp = opportunity();
        Quotation sent = new Quotation();
        sent.setOpportunityId(11L);
        sent.setStatus("SENT");
        when(quotationMapper.selectList(any())).thenReturn(List.of(sent));

        assertThatThrownBy(() -> service.ensureStageAllowed(opp, "初步接触"))
                .hasMessageContaining("不能回退");
        org.assertj.core.api.Assertions.assertThatCode(() -> service.ensureStageAllowed(opp, "已成交"))
                .doesNotThrowAnyException();
    }

    @Test
    void 逾期天数按自然日计算() {
        SalesOpportunity opp = opportunity();
        opp.setNextFollowUpAt(LocalDateTime.of(2026, 11, 10, 15, 0));

        assertThat(service.overdueDays(opp, LocalDate.of(2026, 11, 13))).isEqualTo(3);
        assertThat(service.overdueDays(opp, LocalDate.of(2026, 11, 10))).isZero();
    }

    @Test
    void 每人每天三条上限按目标人与当天统计() {
        assertThat(service.promptsSentToday(null)).isZero();
        when(promptMapper.selectCount(any())).thenReturn(3L);

        assertThat(service.promptsSentToday(7L)).isEqualTo(3);
    }

    // ==================== next_follow_up_at 一次性回填 ====================

    private SalesOpportunity legacy(String text) {
        SalesOpportunity opp = opportunity();
        opp.setNextFollowUp(text);
        opp.setNextFollowUpAt(null);
        return opp;
    }

    @Test
    void 回填把能解析的文本写成结构化时间() {
        SalesOpportunity row = legacy("明天 15:30 再打电话");
        when(opportunityMapper.selectList(any())).thenReturn(List.of(row));

        OpportunityFollowUpService.BackfillResult result = service.backfillNextFollowUpAt();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.filled()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(row.getNextFollowUpAt()).isEqualTo(LocalDate.now().plusDays(1).atTime(15, 30));
        verify(opportunityMapper).updateById(row);
    }

    @Test
    void 回填解析不出的保留为空且不写库() {
        SalesOpportunity row = legacy("好的收到，我再想想");
        when(opportunityMapper.selectList(any())).thenReturn(List.of(row));

        OpportunityFollowUpService.BackfillResult result = service.backfillNextFollowUpAt();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.filled()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(row.getNextFollowUpAt()).isNull();
        verify(opportunityMapper, never()).updateById(any());
    }

    @Test
    void 回填不覆盖已经有下次跟进时间的行() {
        SalesOpportunity filled = opportunity();
        filled.setNextFollowUp("明天 09:00");
        filled.setNextFollowUpAt(LocalDateTime.of(2026, 11, 12, 9, 0));
        when(opportunityMapper.selectList(any())).thenReturn(List.of(filled));

        OpportunityFollowUpService.BackfillResult result = service.backfillNextFollowUpAt();

        assertThat(result.filled()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(filled.getNextFollowUpAt()).isEqualTo(LocalDateTime.of(2026, 11, 12, 9, 0));
        verify(opportunityMapper, never()).updateById(any());
    }

    @Test
    void 提醒排除名单含已流失与暂停推进但不含进行中阶段() {
        assertThat(OpportunityFollowUpService.REMINDER_EXCLUDED_STATUSES)
                .contains("已成交", "已流失", "已输单", "已关闭", "暂停推进")
                .doesNotContain("初步接触", "需求确认", "方案报价", "商务谈判");
    }
}
