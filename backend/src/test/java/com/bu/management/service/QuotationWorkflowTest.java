package com.bu.management.service;

import com.bu.management.entity.Quotation;
import com.bu.management.entity.QuotationApprovalLog;
import com.bu.management.entity.QuotationDiscountRequest;
import com.bu.management.mapper.QuotationApprovalLogMapper;
import com.bu.management.mapper.QuotationDiscountRequestMapper;
import com.bu.management.mapper.QuotationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotationWorkflowTest {

    @Mock private QuotationMapper quotationMapper;
    @Mock private QuotationApprovalLogMapper approvalLogMapper;
    @Mock private QuotationDiscountRequestMapper discountRequestMapper;
    @Mock private SalesOpportunityLinkService opportunityLink;

    private QuotationWorkflowService workflow;

    @BeforeEach
    void setUp() {
        workflow = new QuotationWorkflowService(quotationMapper, approvalLogMapper,
                discountRequestMapper, opportunityLink);
    }

    private Quotation quotation(String status) {
        Quotation q = new Quotation();
        q.setId(1L);
        q.setStatus(status);
        q.setQuoteDate(LocalDate.now());
        q.setValidityDays(30);
        q.setDiscountOverride(0);
        return q;
    }

    @Test
    void rejectingIllegalTransition() {
        when(quotationMapper.selectById(1L)).thenReturn(quotation("DRAFT"));

        assertThatThrownBy(() -> workflow.transition(1L, "SENT", "tester", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("无效的状态流转");
        verify(quotationMapper, never()).updateById(any());
    }

    @Test
    void approvingWithDiscountOverrideRequiresApprovedDiscountRequest() {
        Quotation q = quotation("INTERNAL_REVIEW");
        q.setDiscountOverride(1);
        when(quotationMapper.selectById(1L)).thenReturn(q);
        when(discountRequestMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> workflow.transition(1L, "APPROVED", "tester", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("折扣审批");
        verify(quotationMapper, never()).updateById(any());
    }

    @Test
    void approvingWithDiscountOverridePassesWhenDiscountApproved() {
        Quotation q = quotation("INTERNAL_REVIEW");
        q.setDiscountOverride(1);
        when(quotationMapper.selectById(1L)).thenReturn(q);
        QuotationDiscountRequest approved = new QuotationDiscountRequest();
        approved.setStatus("APPROVED");
        when(discountRequestMapper.selectOne(any())).thenReturn(approved);

        workflow.transition(1L, "APPROVED", "tester", "同意");

        ArgumentCaptor<Quotation> captor = ArgumentCaptor.forClass(Quotation.class);
        verify(quotationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("APPROVED");
        verify(approvalLogMapper).insert(any());
    }

    @Test
    void rejectedQuotationCanBeReturnedToDraft() {
        Quotation q = quotation("REJECTED");
        when(quotationMapper.selectById(1L)).thenReturn(q);

        workflow.transition(1L, "DRAFT", "tester", "客户要求重报");

        assertThat(q.getStatus()).isEqualTo("DRAFT");
        assertThat(q.getIsPreferred()).isZero();
    }

    @Test
    void expiringOverdueSentQuotation() {
        Quotation q = quotation("SENT");
        q.setQuoteDate(LocalDate.now().minusDays(40));
        q.setValidityDays(30);
        when(quotationMapper.selectList(any())).thenReturn(List.of(q));

        List<Long> expired = workflow.expireOverdue();

        assertThat(expired).containsExactly(1L);
        assertThat(q.getStatus()).isEqualTo("EXPIRED");
        ArgumentCaptor<QuotationApprovalLog> logCaptor = ArgumentCaptor.forClass(QuotationApprovalLog.class);
        verify(approvalLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getAction()).isEqualTo("EXPIRE");
    }

    @Test
    void availableTransitionsReflectStatusMachine() {
        assertThat(workflow.availableTransitions(quotation("DRAFT"))).containsExactly("INTERNAL_REVIEW");
        assertThat(workflow.availableTransitions(quotation("ACCEPTED"))).isEmpty();
    }
}
