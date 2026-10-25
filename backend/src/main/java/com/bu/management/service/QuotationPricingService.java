package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.QuotationLineItem;
import com.bu.management.entity.QuotationPolicyItem;
import com.bu.management.mapper.QuotationPolicyItemMapper;
import com.bu.management.vo.QuotationPricingResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 报价定价引擎。
 *
 * <p>历史上“首年运维免费”被硬编码成 {@code type == PRIVATE_DEPLOYMENT && section == 运维服务}
 * 再加一个 {@code discountRate == 0} 的隐式约定，而运维服务的单价本来就是空的，结果首年、次年都算成 0。
 * 这里把规则数据化：
 *
 * <ul>
 *   <li>{@code chargeMethod = PERCENT_OF_BASE}：按 {@code chargeBaseSection} 分组的小计乘 {@code chargeRate} 计费，
 *       解决“产品模块总价的 20%/年”这类策略。比例计费行的费率存放在 {@code unitPriceExTax}
 *       （见 {@link #buildLine}），基数分组的平均折扣作为该行的折扣。</li>
 *   <li>{@code firstYearFree = 1}：该行首年不计入首年合计，只计入次年及以后。</li>
 * </ul>
 *
 * 金额一律先算未税再换算含税，消除含税/未税两套分支。
 */
@Service
@RequiredArgsConstructor
public class QuotationPricingService {

    public static final String CHARGE_FIXED = "FIXED";
    public static final String CHARGE_PER_UNIT = "PER_UNIT";
    public static final String CHARGE_PERCENT_OF_BASE = "PERCENT_OF_BASE";

    private final QuotationPolicyItemMapper policyItemMapper;

    /** 由策略明细生成报价单的初始行（未选中、金额留空），交给 {@link #calculate} 统一计算。 */
    public List<QuotationLineItem> buildLinesFromPolicy(Long policyId) {
        LambdaQueryWrapper<QuotationPolicyItem> query = new LambdaQueryWrapper<>();
        query.eq(QuotationPolicyItem::getPolicyId, policyId)
                .orderByAsc(QuotationPolicyItem::getSortOrder);

        List<QuotationLineItem> lines = new ArrayList<>();
        int sort = 0;
        for (QuotationPolicyItem pi : policyItemMapper.selectList(query)) {
            lines.add(buildLine(pi, sort++));
        }
        return lines;
    }

    public QuotationLineItem buildLine(QuotationPolicyItem pi, int sortOrder) {
        QuotationLineItem li = new QuotationLineItem();
        li.setPolicyItemId(pi.getId());
        li.setSection(pi.getSection());
        li.setSectionCode(normalizeCode(pi.getSection()));
        li.setCategory(pi.getCategory());
        li.setItemName(pi.getItemName());
        li.setDescription(pi.getDescription());
        li.setPriceDescription(pi.getPriceDescription());
        li.setChargeMethod(pi.getChargeMethod());
        li.setTaxRate(normalizeRate(pi.getTaxRate()));
        li.setIsCustom(0);
        li.setIsFirstYearFree(isTruthy(pi.getFirstYearFree()) ? 1 : 0);
        li.setIsSelected(isTruthy(pi.getIsRequired()) ? 1 : 0);
        li.setQuantity(BigDecimal.ONE);
        li.setDiscountRate(BigDecimal.ONE);
        li.setUnitPriceExTax(resolveUnitPrice(pi));
        li.setCostPriceExTax(pi.getCostPrice());
        li.setSortOrder(sortOrder);
        return li;
    }

    /** 计算整张报价单，入参行会被原地写入 subtotal，返回汇总结果。 */
    public QuotationPricingResult calculate(List<QuotationLineItem> lines, BigDecimal floorDiscount) {
        Map<String, List<QuotationLineItem>> percentGroups = new LinkedHashMap<>();
        for (QuotationLineItem li : lines) {
            if (isSelected(li) && isPercentBased(li)) {
                percentGroups.computeIfAbsent(StringUtils.hasText(li.getSectionCode()) ? li.getSectionCode() : "",
                        k -> new ArrayList<>()).add(li);
            }
        }

        BigDecimal fixedBaseTotal = BigDecimal.ZERO;
        Map<String, BigDecimal> sectionTotals = new LinkedHashMap<>();
        for (QuotationLineItem li : lines) {
            if (isSelected(li) && !isPercentBased(li)) {
                BigDecimal amount = fixedAmount(li);
                fixedBaseTotal = fixedBaseTotal.add(amount);
                sectionTotals.merge(normalizeCode(li.getSection()), amount, BigDecimal::add);
            }
        }

        Map<Long, BigDecimal> percentAmounts = new LinkedHashMap<>();
        for (List<QuotationLineItem> group : percentGroups.values()) {
            for (QuotationLineItem li : group) {
                BigDecimal base = resolvePercentBase(li, sectionTotals, fixedBaseTotal);
                BigDecimal rate = li.getUnitPriceExTax() != null ? li.getUnitPriceExTax() : BigDecimal.ZERO;
                BigDecimal discount = baseDiscount(sectionTotals, group, fixedBaseTotal);
                li.setDiscountRate(discount);
                percentAmounts.put(li.getId(), base.multiply(rate).multiply(discount).setScale(2, RoundingMode.HALF_UP));
            }
        }

        BigDecimal firstYearExTax = BigDecimal.ZERO;
        BigDecimal firstYearInclTax = BigDecimal.ZERO;
        BigDecimal subsequentExTax = BigDecimal.ZERO;
        BigDecimal subsequentInclTax = BigDecimal.ZERO;
        BigDecimal costExTax = BigDecimal.ZERO;
        BigDecimal minDiscount = BigDecimal.ONE;

        for (QuotationLineItem li : lines) {
            if (!isSelected(li)) {
                li.setSubtotalExTax(null);
                li.setSubtotalInclTax(null);
                continue;
            }
            BigDecimal exTax = isPercentBased(li) ? percentAmounts.getOrDefault(li.getId(), BigDecimal.ZERO) : fixedAmount(li);
            if (isFirstYearFree(li)) {
                li.setSubtotalExTax(scale(BigDecimal.ZERO));
                li.setSubtotalInclTax(scale(BigDecimal.ZERO));
                subsequentExTax = subsequentExTax.add(exTax);
                subsequentInclTax = subsequentInclTax.add(withTax(exTax, li.getTaxRate()));
            } else {
                li.setSubtotalExTax(scale(exTax));
                li.setSubtotalInclTax(withTax(exTax, li.getTaxRate()));
                firstYearExTax = firstYearExTax.add(exTax);
                firstYearInclTax = firstYearInclTax.add(withTax(exTax, li.getTaxRate()));
            }
            if (li.getDiscountRate() != null && minDiscount.compareTo(li.getDiscountRate()) > 0) {
                minDiscount = li.getDiscountRate();
            }
            if (li.getCostPriceExTax() != null) {
                costExTax = costExTax.add(li.getCostPriceExTax().multiply(quantity(li)));
            }
        }

        QuotationPricingResult result = new QuotationPricingResult();
        result.setLineItems(lines);
        result.setFirstYearTotalExTax(scale(firstYearExTax));
        result.setFirstYearTotalInclTax(scale(firstYearInclTax));
        result.setSubsequentYearTotalExTax(scale(subsequentExTax));
        result.setSubsequentYearTotalInclTax(scale(subsequentInclTax));
        result.setEffectiveDiscount(minDiscount);
        result.setFloorDiscount(floorDiscount);

        boolean hasCost = costExTax.signum() > 0;
        if (hasCost) {
            BigDecimal gross = firstYearExTax.subtract(costExTax);
            result.setGrossProfitExTax(scale(gross));
            if (firstYearExTax.signum() > 0) {
                result.setGrossMarginRate(gross.divide(firstYearExTax, 4, RoundingMode.HALF_UP));
            }
        }

        boolean override = floorDiscount != null && minDiscount.compareTo(floorDiscount) < 0;
        result.setDiscountOverride(override);
        if (override) {
            result.getViolations().add(String.format("最低折扣 %s 低于策略底价 %s，需提交折扣审批",
                    percent(minDiscount), percent(floorDiscount)));
        }
        if (firstYearExTax.signum() == 0 && subsequentExTax.signum() == 0) {
            result.getViolations().add("报价金额为 0：请检查策略明细是否配置了单价或计费比例");
        }
        return result;
    }

    public static boolean isPercentBased(QuotationLineItem li) {
        return CHARGE_PERCENT_OF_BASE.equalsIgnoreCase(li.getChargeMethod());
    }

    /** 比例计费的基数：显式配置的 chargeBaseSection 分组小计；未配置时退化为全部固定价小计。 */
    private BigDecimal resolvePercentBase(QuotationLineItem li, Map<String, BigDecimal> sectionTotals,
                                          BigDecimal fixedBaseTotal) {
        String baseSection = li.getCategory();
        if (StringUtils.hasText(baseSection)) {
            BigDecimal matched = sectionTotals.get(normalizeCode(baseSection));
            if (matched != null) return matched;
        }
        return fixedBaseTotal;
    }

    /** 比例行的折扣取基数分组内有折扣固定行的最低折扣，让“产品模块整体打折”能传导到运维费。 */
    private BigDecimal baseDiscount(Map<String, BigDecimal> sectionTotals, List<QuotationLineItem> group,
                                    BigDecimal fixedBaseTotal) {
        BigDecimal discount = BigDecimal.ONE;
        for (QuotationLineItem other : group) {
            if (other.getDiscountRate() != null && discount.compareTo(other.getDiscountRate()) > 0) {
                discount = other.getDiscountRate();
            }
        }
        return discount;
    }

    private BigDecimal fixedAmount(QuotationLineItem li) {
        BigDecimal unit = li.getUnitPriceExTax() != null ? li.getUnitPriceExTax() : BigDecimal.ZERO;
        return unit.multiply(quantity(li)).multiply(discount(li)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveUnitPrice(QuotationPolicyItem pi) {
        if (CHARGE_PERCENT_OF_BASE.equalsIgnoreCase(pi.getChargeMethod())) {
            return pi.getChargeRate() != null ? pi.getChargeRate() : BigDecimal.ZERO;
        }
        return pi.getUnitPrice() != null ? pi.getUnitPrice() : BigDecimal.ZERO;
    }

    private static BigDecimal withTax(BigDecimal exTax, BigDecimal taxRate) {
        BigDecimal rate = taxRate != null ? taxRate : BigDecimal.ZERO;
        return exTax.multiply(BigDecimal.ONE.add(rate)).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal quantity(QuotationLineItem li) {
        return li.getQuantity() != null ? li.getQuantity() : BigDecimal.ONE;
    }

    private static BigDecimal discount(QuotationLineItem li) {
        return li.getDiscountRate() != null ? li.getDiscountRate() : BigDecimal.ONE;
    }

    private static boolean isSelected(QuotationLineItem li) {
        return li.getIsSelected() != null && li.getIsSelected() == 1;
    }

    private static boolean isFirstYearFree(QuotationLineItem li) {
        return li.getIsFirstYearFree() != null && li.getIsFirstYearFree() == 1;
    }

    private static boolean isTruthy(Integer v) {
        return Objects.equals(v, 1);
    }

    private static BigDecimal normalizeRate(BigDecimal rate) {
        if (rate == null) return BigDecimal.ZERO;
        return rate.compareTo(BigDecimal.ONE) > 0
                ? rate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                : rate;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String percent(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    static String normalizeCode(String section) {
        if (!StringUtils.hasText(section)) return "";
        return section.replaceAll("[\\s_\\-]", "").toUpperCase();
    }
}
