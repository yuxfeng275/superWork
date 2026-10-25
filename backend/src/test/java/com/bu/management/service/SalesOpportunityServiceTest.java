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
import java.time.LocalDateTime;

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

    private SalesOpportunityService service;

    @BeforeEach
    void setUp() {
        service = new SalesOpportunityService(opportunityMapper, supportWorkLogMapper, followUpMapper, quotationMapper);
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
