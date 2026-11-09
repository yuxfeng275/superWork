package com.bu.management.service;

import com.bu.management.dto.SalesOpportunityFollowUpRequest;
import com.bu.management.dto.SalesOpportunitySupportWorkLogRequest;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.mapper.SalesOpportunityFollowUpMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.bu.management.mapper.SalesOpportunitySupportWorkLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesOpportunityServiceTest {

    @Mock private SalesOpportunityMapper opportunityMapper;
    @Mock private SalesOpportunitySupportWorkLogMapper supportWorkLogMapper;
    @Mock private SalesOpportunityFollowUpMapper followUpMapper;
    @Mock private com.bu.management.mapper.QuotationMapper quotationMapper;
    @Mock private com.bu.management.mapper.UserMapper userMapper;

    private SalesOpportunityService service;

    @BeforeEach
    void setUp() {
        service = new SalesOpportunityService(opportunityMapper, supportWorkLogMapper, followUpMapper,
                quotationMapper, userMapper);
    }

    /** 默认文案口径：MM-dd 周X。 */
    private static String defaultText(LocalDate date) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return String.format("%02d-%02d %s", date.getMonthValue(), date.getDayOfMonth(),
                names[date.getDayOfWeek().getValue() - 1]);
    }

    @Test
    void createFollowUpAppendsHistoryAndUpdatesCurrentOpportunitySnapshot() {
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(7L);
        opportunity.setName("皇家续约");
        opportunity.setStatus("需求确认");
        opportunity.setProbability(30);
        opportunity.setNextFollowUp("周三");
        opportunity.setNote("商机长期备注");
        when(opportunityMapper.selectById(7L)).thenReturn(opportunity);
        com.bu.management.entity.Quotation approved = new com.bu.management.entity.Quotation();
        approved.setOpportunityId(7L);
        approved.setStatus("APPROVED");
        when(quotationMapper.selectList(any())).thenReturn(java.util.List.of(approved));

        SalesOpportunityFollowUpRequest request = new SalesOpportunityFollowUpRequest();
        request.setFollowUpAt(LocalDateTime.of(2026, 8, 10, 14, 30));
        request.setFollower("系统管理员");
        request.setContent("客户认可一期范围，等待采购确认预算");
        request.setStatus("商务谈判");
        request.setProbability(70);
        request.setNextFollowUp("周五 15:00");

        SalesOpportunityFollowUp result = service.createFollowUp(7L, request);

        ArgumentCaptor<SalesOpportunityFollowUp> historyCaptor = ArgumentCaptor.forClass(SalesOpportunityFollowUp.class);
        verify(followUpMapper).insert(historyCaptor.capture());
        SalesOpportunityFollowUp history = historyCaptor.getValue();
        assertThat(history.getOpportunityId()).isEqualTo(7L);
        assertThat(history.getFollowUpAt()).isEqualTo(request.getFollowUpAt());
        assertThat(history.getFollower()).isEqualTo("系统管理员");
        assertThat(history.getContent()).isEqualTo(request.getContent());
        assertThat(history.getStatus()).isEqualTo("商务谈判");
        assertThat(history.getProbability()).isEqualTo(70);
        assertThat(history.getNextFollowUp()).isEqualTo("周五 15:00");
        assertThat(result).isSameAs(history);

        ArgumentCaptor<SalesOpportunity> opportunityCaptor = ArgumentCaptor.forClass(SalesOpportunity.class);
        verify(opportunityMapper).updateById(opportunityCaptor.capture());
        SalesOpportunity updated = opportunityCaptor.getValue();
        assertThat(updated.getStatus()).isEqualTo("商务谈判");
        assertThat(updated.getProbability()).isEqualTo(70);
        assertThat(updated.getNextFollowUp()).isEqualTo("周五 15:00");
        assertThat(updated.getNote()).isEqualTo("商机长期备注");
    }

    @Test
    void createFollowUpRejectsBlankContentWithoutWritingHistory() {
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(7L);
        when(opportunityMapper.selectById(7L)).thenReturn(opportunity);
        SalesOpportunityFollowUpRequest request = new SalesOpportunityFollowUpRequest();
        request.setFollower("系统管理员");
        request.setContent("  ");
        request.setStatus("需求确认");
        request.setProbability(30);

        assertThatThrownBy(() -> service.createFollowUp(7L, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("跟进内容不能为空");
        verify(followUpMapper, never()).insert(any());
        verify(opportunityMapper, never()).updateById(any());
    }

    @Test
    void createFollowUpRejectsQuoteStageWithoutApprovedQuotation() {
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(7L);
        opportunity.setStatus("需求确认");
        when(opportunityMapper.selectById(7L)).thenReturn(opportunity);
        when(quotationMapper.selectList(any())).thenReturn(java.util.List.of());

        SalesOpportunityFollowUpRequest request = new SalesOpportunityFollowUpRequest();
        request.setFollower("系统管理员");
        request.setContent("客户进入商务谈判");
        request.setStatus("商务谈判");
        request.setProbability(70);

        assertThatThrownBy(() -> service.createFollowUp(7L, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("报价单的审批");
        verify(followUpMapper, never()).insert(any());
    }

    @Test
    void createFollowUpAllowsQuoteStageBeforeQuotationExists() {
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(7L);
        opportunity.setStatus("需求确认");
        when(opportunityMapper.selectById(7L)).thenReturn(opportunity);
        when(quotationMapper.selectList(any())).thenReturn(java.util.List.of());

        SalesOpportunityFollowUpRequest request = new SalesOpportunityFollowUpRequest();
        request.setFollower("系统管理员");
        request.setContent("客户口头询价");
        request.setStatus("方案报价");
        request.setProbability(50);

        service.createFollowUp(7L, request);

        verify(followUpMapper).insert(any());
        assertThat(opportunity.getStatus()).isEqualTo("方案报价");
    }

    @Test
    void createFollowUpRejectsRegressionAfterQuotationSent() {
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(7L);
        opportunity.setStatus("商务谈判");
        when(opportunityMapper.selectById(7L)).thenReturn(opportunity);
        com.bu.management.entity.Quotation sent = new com.bu.management.entity.Quotation();
        sent.setOpportunityId(7L);
        sent.setStatus("SENT");
        when(quotationMapper.selectList(any())).thenReturn(java.util.List.of(sent));

        SalesOpportunityFollowUpRequest request = new SalesOpportunityFollowUpRequest();
        request.setFollower("系统管理员");
        request.setContent("回退");
        request.setStatus("初步接触");
        request.setProbability(20);

        assertThatThrownBy(() -> service.createFollowUp(7L, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("不能回退");
    }

    @Test
    void createFollowUpParsesNextFollowUpTextIntoStructuredTime() {
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(7L);
        opportunity.setName("皇家续约");
        opportunity.setStatus("需求确认");
        when(opportunityMapper.selectById(7L)).thenReturn(opportunity);

        SalesOpportunityFollowUpRequest request = new SalesOpportunityFollowUpRequest();
        request.setFollower("系统管理员");
        request.setContent("约好下周过方案");
        request.setStatus("需求确认");
        request.setProbability(40);
        request.setNextFollowUp("下周三 15:00");

        service.createFollowUp(7L, request);

        assertThat(opportunity.getNextFollowUpAt()).isNotNull();
        assertThat(opportunity.getNextFollowUpAt().getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(opportunity.getNextFollowUpAt().toLocalTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(opportunity.getNextFollowUpAt().toLocalDate()).isAfter(LocalDate.now());
        assertThat(opportunity.getFollowUpState()).isEqualTo("PENDING");
    }

    @Test
    void createFollowUpFillsWeeklyDefaultWhenTextUnparseable() {
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(7L);
        opportunity.setStatus("需求确认");
        // 上一次排的计划还在；手填了但解析不出 → 按周推算的默认值覆盖，不留旧时间
        opportunity.setNextFollowUpAt(LocalDateTime.now().plusDays(1).withHour(10).withMinute(0));
        when(opportunityMapper.selectById(7L)).thenReturn(opportunity);

        SalesOpportunityFollowUpRequest request = new SalesOpportunityFollowUpRequest();
        request.setFollower("系统管理员");
        request.setContent("客户让再看");
        request.setStatus("需求确认");
        request.setProbability(30);
        request.setNextFollowUp("再看");

        LocalDate expectedDate = LocalDate.now().plusDays(7);
        SalesOpportunityFollowUp result = service.createFollowUp(7L, request);

        assertThat(opportunity.getNextFollowUpAt()).isEqualTo(expectedDate.atTime(10, 0));
        assertThat(opportunity.getNextFollowUp()).isEqualTo(defaultText(expectedDate));
        assertThat(result.getNextFollowUp()).isEqualTo(defaultText(expectedDate));
        assertThat(opportunity.getFollowUpState()).isEqualTo("PENDING");
    }

    @Test
    void createFollowUpDefaultsToNextWeekSameDayAtTen() {
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(7L);
        opportunity.setStatus("需求确认");
        when(opportunityMapper.selectById(7L)).thenReturn(opportunity);

        SalesOpportunityFollowUpRequest request = new SalesOpportunityFollowUpRequest();
        request.setFollower("系统管理员");
        request.setContent("周二例会跟进完");
        request.setStatus("需求确认");
        request.setProbability(30);
        // 2026-08-11 是周二 → 默认下周同一天 2026-08-18 10:00，文案 08-18 周二
        request.setFollowUpAt(LocalDateTime.of(2026, 8, 11, 14, 30));

        SalesOpportunityFollowUp result = service.createFollowUp(7L, request);

        assertThat(opportunity.getNextFollowUpAt()).isEqualTo(LocalDateTime.of(2026, 8, 18, 10, 0));
        assertThat(opportunity.getNextFollowUpAt().getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(opportunity.getNextFollowUp()).isEqualTo("08-18 周二");
        assertThat(result.getNextFollowUp()).isEqualTo("08-18 周二");
    }

    @Test
    void createFollowUpWithoutNextFollowUpTextLeavesOtherFieldsAlone() {
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(7L);
        opportunity.setName("皇家续约");
        opportunity.setNote("商机长期备注");
        opportunity.setStatus("需求确认");
        opportunity.setNextFollowUpAt(LocalDateTime.now().plusDays(2));
        when(opportunityMapper.selectById(7L)).thenReturn(opportunity);

        SalesOpportunityFollowUpRequest request = new SalesOpportunityFollowUpRequest();
        request.setFollower("系统管理员");
        request.setContent("电话沟通了预算");
        request.setStatus("需求确认");
        request.setProbability(35);

        LocalDate expectedDate = LocalDate.now().plusDays(7);
        service.createFollowUp(7L, request);

        assertThat(opportunity.getName()).isEqualTo("皇家续约");
        assertThat(opportunity.getNote()).isEqualTo("商机长期备注");
        assertThat(opportunity.getStatus()).isEqualTo("需求确认");
        assertThat(opportunity.getProbability()).isEqualTo(35);
        // 没填下次跟进 → 默认今天 + 7 天 10:00
        assertThat(opportunity.getNextFollowUpAt()).isEqualTo(expectedDate.atTime(10, 0));
        assertThat(opportunity.getNextFollowUp()).isEqualTo(defaultText(expectedDate));
        assertThat(opportunity.getFollowUpState()).isEqualTo("PENDING");
    }

    @Test
    void updateParsesNextFollowUpTextIntoStructuredTime() {
        SalesOpportunity existing = new SalesOpportunity();
        existing.setId(9L);
        existing.setName("华东扩容");
        existing.setStatus("需求确认");
        when(opportunityMapper.selectById(9L)).thenReturn(existing);

        com.bu.management.dto.SalesOpportunityRequest request = new com.bu.management.dto.SalesOpportunityRequest();
        request.setName("华东扩容");
        request.setStatus("需求确认");
        request.setNextFollowUp("下周三 15:00");

        service.update(9L, request);

        assertThat(existing.getNextFollowUpAt()).isNotNull();
        assertThat(existing.getNextFollowUpAt().getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(existing.getNextFollowUpAt().toLocalTime()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    void createDefaultsToNextWeekWhenTextUnparseable() {
        com.bu.management.dto.SalesOpportunityRequest request = new com.bu.management.dto.SalesOpportunityRequest();
        request.setName("新商机");
        request.setStatus("初步接触");
        request.setNextFollowUp("再看");

        LocalDate expectedDate = LocalDate.now().plusDays(7);
        SalesOpportunity created = service.create(request);

        assertThat(created.getNextFollowUp()).isEqualTo(defaultText(expectedDate));
        assertThat(created.getNextFollowUpAt()).isEqualTo(expectedDate.atTime(10, 0));
    }

    @Test
    void createDefaultsNextFollowUpEvenWhenTextMissing() {
        com.bu.management.dto.SalesOpportunityRequest request = new com.bu.management.dto.SalesOpportunityRequest();
        request.setName("新商机");
        request.setStatus("初步接触");

        LocalDate expectedDate = LocalDate.now().plusDays(7);
        SalesOpportunity created = service.create(request);

        assertThat(created.getNextFollowUp()).isEqualTo(defaultText(expectedDate));
        assertThat(created.getNextFollowUpAt()).isEqualTo(expectedDate.atTime(10, 0));
    }

    @Test
    void updateKeepsExistingNextFollowUpWhenRequestLeavesItBlank() {
        SalesOpportunity existing = new SalesOpportunity();
        existing.setId(9L);
        existing.setName("华东扩容");
        existing.setStatus("需求确认");
        existing.setNextFollowUp("11-17 周一");
        existing.setNextFollowUpAt(LocalDateTime.of(2026, 11, 17, 10, 0));
        existing.setOwnerUserId(42L);
        when(opportunityMapper.selectById(9L)).thenReturn(existing);

        com.bu.management.dto.SalesOpportunityRequest request = new com.bu.management.dto.SalesOpportunityRequest();
        request.setName("华东扩容");
        request.setStatus("需求确认");
        request.setOwner("查无此人");

        service.update(9L, request);

        // 编辑不填下次跟进 → 不覆盖已有值（避免误清空）
        assertThat(existing.getNextFollowUp()).isEqualTo("11-17 周一");
        assertThat(existing.getNextFollowUpAt()).isEqualTo(LocalDateTime.of(2026, 11, 17, 10, 0));
        // owner 本次匹配不上 → 保留旧值，不主动清空
        assertThat(existing.getOwnerUserId()).isEqualTo(42L);
    }

    @Test
    void createMatchesOwnerUserIdOnlyWhenRealNameUniquelyEquals() {
        com.bu.management.entity.User owner = new com.bu.management.entity.User();
        owner.setId(7L);
        owner.setRealName("于丽娟");
        when(userMapper.selectList(any())).thenReturn(java.util.List.of(owner));

        com.bu.management.dto.SalesOpportunityRequest request = new com.bu.management.dto.SalesOpportunityRequest();
        request.setName("皇家续约");
        request.setStatus("初步接触");
        request.setOwner("于丽娟");

        SalesOpportunity created = service.create(request);

        assertThat(created.getOwnerUserId()).isEqualTo(7L);
    }

    @Test
    void createLeavesOwnerUserIdNullWhenNameMatchesNobodyOrSeveral() {
        com.bu.management.entity.User first = new com.bu.management.entity.User();
        first.setId(7L);
        first.setRealName("刘晓文");
        com.bu.management.entity.User second = new com.bu.management.entity.User();
        second.setId(8L);
        second.setRealName("刘晓文");
        when(userMapper.selectList(any())).thenReturn(java.util.List.of(first, second));

        com.bu.management.dto.SalesOpportunityRequest request = new com.bu.management.dto.SalesOpportunityRequest();
        request.setName("皇家续约");
        request.setStatus("初步接触");
        request.setOwner("刘晓文");

        SalesOpportunity created = service.create(request);

        // 命中多条 = 不唯一 → 不猜，留空让问句走站内兜底
        assertThat(created.getOwnerUserId()).isNull();
    }

    @Test
    void createSupportWorkLogRejectsWonOpportunity() {
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(7L);
        opportunity.setStatus("已成交");
        when(opportunityMapper.selectById(7L)).thenReturn(opportunity);

        SalesOpportunitySupportWorkLogRequest request = new SalesOpportunitySupportWorkLogRequest();
        request.setSupporter("王工");
        request.setHours(BigDecimal.ONE);
        request.setContent("方案支持");

        assertThatThrownBy(() -> service.createSupportWorkLog(7L, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("已成交");
        verify(supportWorkLogMapper, never()).insert(any());
    }
}
