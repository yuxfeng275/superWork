package com.bu.management.service;

import com.bu.management.dto.SalesOpportunityFollowUpRequest;
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

    private SalesOpportunityService service;

    @BeforeEach
    void setUp() {
        service = new SalesOpportunityService(opportunityMapper, supportWorkLogMapper, followUpMapper);
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
}
