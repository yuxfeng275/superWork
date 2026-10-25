package com.bu.management.service;

import com.bu.management.entity.QuotationLineItem;
import com.bu.management.mapper.QuotationPolicyItemMapper;
import com.bu.management.vo.QuotationPricingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class QuotationPricingServiceTest {

    @Mock private QuotationPolicyItemMapper policyItemMapper;
    private QuotationPricingService pricing;

    @BeforeEach
    void setUp() {
        pricing = new QuotationPricingService(policyItemMapper);
    }

    private static QuotationLineItem line(String section, String amount, String taxRate, String discount) {
        QuotationLineItem li = new QuotationLineItem();
        li.setSection(section);
        li.setSectionCode(QuotationPricingService.normalizeCode(section));
        li.setChargeMethod(QuotationPricingService.CHARGE_FIXED);
        li.setIsSelected(1);
        li.setIsCustom(0);
        li.setIsFirstYearFree(0);
        li.setQuantity(BigDecimal.ONE);
        li.setUnitPriceExTax(new BigDecimal(amount));
        li.setTaxRate(new BigDecimal(taxRate));
        li.setDiscountRate(new BigDecimal(discount));
        return li;
    }

    private static QuotationLineItem percentLine(String section, String category, String rate) {
        QuotationLineItem li = new QuotationLineItem();
        li.setSection(section);
        li.setSectionCode(QuotationPricingService.normalizeCode(section));
        li.setCategory(category);
        li.setChargeMethod(QuotationPricingService.CHARGE_PERCENT_OF_BASE);
        li.setIsSelected(1);
        li.setIsFirstYearFree(1);
        li.setQuantity(BigDecimal.ONE);
        li.setUnitPriceExTax(new BigDecimal(rate));
        li.setTaxRate(new BigDecimal("0.06"));
        li.setDiscountRate(BigDecimal.ONE);
        return li;
    }

    @Test
    void percentBasedMaintenanceIsChargedOnModuleSubtotalAndOnlyCountedInSubsequentYear() {
        QuotationLineItem cdp = line("产品模块", "180000", "0.13", "1");
        QuotationLineItem loyalty = line("产品模块", "50000", "0.13", "1");
        QuotationLineItem ops = percentLine("运维服务", "产品模块", "0.2");

        QuotationPricingResult result = pricing.calculate(List.of(cdp, loyalty, ops), null);

        assertThat(result.getFirstYearTotalExTax()).isEqualByComparingTo("230000.00");
        assertThat(result.getSubsequentYearTotalExTax()).isEqualByComparingTo("46000.00");
        assertThat(ops.getSubtotalExTax()).isEqualByComparingTo("0.00");
        assertThat(ops.getSubtotalInclTax()).isEqualByComparingTo("0.00");
        assertThat(result.getViolations()).isEmpty();
    }

    @Test
    void moduleDiscountPropagatesToPercentBasedMaintenance() {
        QuotationLineItem cdp = line("产品模块", "100000", "0.13", "0.9");
        QuotationLineItem ops = percentLine("运维服务", "产品模块", "0.2");

        QuotationPricingResult result = pricing.calculate(List.of(cdp, ops), null);

        assertThat(result.getFirstYearTotalExTax()).isEqualByComparingTo("90000.00");
        assertThat(result.getSubsequentYearTotalExTax()).isEqualByComparingTo("18000.00");
    }

    @Test
    void taxIncludedTotalIsDerivedFromTaxExcludedTotal() {
        QuotationLineItem li = line("产品模块", "10000", "0.06", "1");

        QuotationPricingResult result = pricing.calculate(List.of(li), null);

        assertThat(result.getFirstYearTotalExTax()).isEqualByComparingTo("10000.00");
        assertThat(result.getFirstYearTotalInclTax()).isEqualByComparingTo("10600.00");
    }

    @Test
    void discountBelowFloorIsFlagged() {
        QuotationLineItem li = line("产品模块", "100000", "0.06", "0.7");

        QuotationPricingResult result = pricing.calculate(List.of(li), new BigDecimal("0.8"));

        assertThat(result.isDiscountOverride()).isTrue();
        assertThat(result.getViolations()).anyMatch(v -> v.contains("低于策略底价"));
    }

    @Test
    void zeroAmountQuoteIsReported() {
        QuotationPricingResult result = pricing.calculate(List.of(), null);

        assertThat(result.getFirstYearTotalExTax()).isEqualByComparingTo("0");
        assertThat(result.getViolations()).anyMatch(v -> v.contains("报价金额为 0"));
    }

    @Test
    void grossMarginUsesCostPriceWhenPresent() {
        QuotationLineItem li = line("产品模块", "100000", "0.06", "1");
        li.setCostPriceExTax(new BigDecimal("60000"));

        QuotationPricingResult result = pricing.calculate(List.of(li), null);

        assertThat(result.getGrossProfitExTax()).isEqualByComparingTo("40000.00");
        assertThat(result.getGrossMarginRate()).isEqualByComparingTo("0.4000");
    }

    @Test
    void unselectedLinesAreIgnored() {
        QuotationLineItem selected = line("产品模块", "1000", "0.06", "1");
        QuotationLineItem skipped = line("定制开发", "99999", "0.06", "1");
        skipped.setIsSelected(0);

        QuotationPricingResult result = pricing.calculate(List.of(selected, skipped), null);

        assertThat(result.getFirstYearTotalExTax()).isEqualByComparingTo("1000.00");
        assertThat(skipped.getSubtotalExTax()).isNull();
    }
}
