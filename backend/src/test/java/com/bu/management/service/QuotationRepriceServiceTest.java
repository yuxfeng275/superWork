package com.bu.management.service;

import com.bu.management.entity.Quotation;
import com.bu.management.entity.QuotationLineItem;
import com.bu.management.entity.QuotationPolicy;
import com.bu.management.mapper.QuotationLineItemMapper;
import com.bu.management.mapper.QuotationMapper;
import com.bu.management.mapper.QuotationPolicyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotationRepriceServiceTest {

    @Mock private QuotationMapper quotationMapper;
    @Mock private QuotationLineItemMapper lineItemMapper;
    @Mock private QuotationPolicyMapper policyMapper;
    @Mock private QuotationPricingService pricingService;

    private QuotationRepriceService reprice;

    @BeforeEach
    void setUp() {
        reprice = new QuotationRepriceService(quotationMapper, lineItemMapper, policyMapper, pricingService);
    }

    private Quotation quotation() {
        Quotation q = new Quotation();
        q.setId(1L);
        q.setPolicyId(9L);
        q.setStatus("DRAFT");
        q.setFirstYearTotalExTax(new BigDecimal("0.00"));
        return q;
    }

    private QuotationLineItem opsLine() {
        QuotationLineItem li = new QuotationLineItem();
        li.setId(11L);
        li.setQuotationId(1L);
        li.setSection("运维服务");
        li.setIsSelected(1);
        li.setIsFirstYearFree(1);
        li.setQuantity(BigDecimal.ONE);
        li.setDiscountRate(BigDecimal.ONE);
        li.setUnitPriceExTax(new BigDecimal("0.2"));
        li.setTaxRate(new BigDecimal("0.06"));
        return li;
    }

    @Test
    void dryRunReportsChangedIdsWithoutWriting() {
        when(quotationMapper.selectList(any())).thenReturn(List.of(quotation()));
        when(lineItemMapper.selectList(any())).thenReturn(List.of(opsLine()));
        QuotationPolicy policy = new QuotationPolicy();
        policy.setId(9L);
        when(policyMapper.selectById(9L)).thenReturn(policy);
        var pricing = new com.bu.management.vo.QuotationPricingResult();
        pricing.setFirstYearTotalExTax(new BigDecimal("230000.00"));
        pricing.setSubsequentYearTotalExTax(new BigDecimal("46000.00"));
        when(pricingService.calculate(any(), any())).thenReturn(pricing);

        List<Long> changed = reprice.repriceAll(false, true);

        assertThat(changed).containsExactly(1L);
        verify(quotationMapper, never()).updateById(any());
    }

    @Test
    void applyWritesRecalculatedTotals() {
        Quotation quotation = quotation();
        when(quotationMapper.selectList(any())).thenReturn(List.of(quotation));
        when(lineItemMapper.selectList(any())).thenReturn(List.of(opsLine()));
        when(policyMapper.selectById(9L)).thenReturn(null);
        var pricing = new com.bu.management.vo.QuotationPricingResult();
        pricing.setFirstYearTotalExTax(new BigDecimal("230000.00"));
        pricing.setFirstYearTotalInclTax(new BigDecimal("259900.00"));
        pricing.setSubsequentYearTotalExTax(new BigDecimal("46000.00"));
        pricing.setSubsequentYearTotalInclTax(new BigDecimal("48760.00"));
        when(pricingService.calculate(any(), any())).thenReturn(pricing);

        List<Long> changed = reprice.repriceAll(false, false);

        assertThat(changed).containsExactly(1L);
        verify(quotationMapper).updateById(quotation);
        assertThat(quotation.getFirstYearTotalExTax()).isEqualByComparingTo("230000.00");
        assertThat(quotation.getSubsequentYearTotalExTax()).isEqualByComparingTo("46000.00");
    }

    @Test
    void unchangedAmountsAreSkipped() {
        Quotation quotation = quotation();
        quotation.setFirstYearTotalExTax(new BigDecimal("230000.00"));
        quotation.setSubsequentYearTotalExTax(new BigDecimal("46000.00"));
        when(quotationMapper.selectList(any())).thenReturn(List.of(quotation));
        when(lineItemMapper.selectList(any())).thenReturn(List.of(opsLine()));
        when(policyMapper.selectById(9L)).thenReturn(null);
        var pricing = new com.bu.management.vo.QuotationPricingResult();
        pricing.setFirstYearTotalExTax(new BigDecimal("230000.00"));
        pricing.setSubsequentYearTotalExTax(new BigDecimal("46000.00"));
        when(pricingService.calculate(any(), any())).thenReturn(pricing);

        assertThat(reprice.repriceAll(false, false)).isEmpty();
        verify(quotationMapper, never()).updateById(any());
    }
}
