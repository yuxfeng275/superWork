package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Quotation;
import com.bu.management.entity.QuotationLineItem;
import com.bu.management.entity.QuotationPolicy;
import com.bu.management.mapper.QuotationLineItemMapper;
import com.bu.management.mapper.QuotationMapper;
import com.bu.management.mapper.QuotationPolicyMapper;
import com.bu.management.vo.QuotationPricingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 存量报价单重算：V109 之前生成的报价单运维金额恒为 0，这里按新定价引擎回填。
 *
 * <p>只重算草稿与审核中的单；已发送/已成交的报价单是客户看到的既成事实，不自动改，
 * 需要的话由业务显式传入状态范围。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotationRepriceService {

    private static final List<String> SAFE_STATUSES = List.of("DRAFT", "INTERNAL_REVIEW", "APPROVED", "EXPIRED");

    private final QuotationMapper quotationMapper;
    private final QuotationLineItemMapper lineItemMapper;
    private final QuotationPolicyMapper policyMapper;
    private final QuotationPricingService pricingService;

    /** @param dryRun true 只返回受影响清单，不写库 */
    @Transactional(rollbackFor = Exception.class)
    public List<Long> repriceAll(boolean includeSent, boolean dryRun) {
        List<String> statuses = new ArrayList<>(SAFE_STATUSES);
        if (includeSent) {
            statuses.add("SENT");
            statuses.add("ACCEPTED");
            statuses.add("REJECTED");
        }
        List<Quotation> targets = quotationMapper.selectList(
                new LambdaQueryWrapper<Quotation>().in(Quotation::getStatus, statuses));

        List<Long> changed = new ArrayList<>();
        for (Quotation quotation : targets) {
            List<QuotationLineItem> lines = lineItemMapper.selectList(
                    new LambdaQueryWrapper<QuotationLineItem>()
                            .eq(QuotationLineItem::getQuotationId, quotation.getId())
                            .orderByAsc(QuotationLineItem::getSortOrder));
            if (lines.isEmpty()) continue;

            QuotationPolicy policy = quotation.getPolicyId() != null
                    ? policyMapper.selectById(quotation.getPolicyId()) : null;
            QuotationPricingResult pricing = pricingService.calculate(lines,
                    policy != null ? policy.getMinDiscountRate() : null);

            boolean amountChanged = differ(quotation.getFirstYearTotalExTax(), pricing.getFirstYearTotalExTax())
                    || differ(quotation.getSubsequentYearTotalExTax(), pricing.getSubsequentYearTotalExTax());
            if (!amountChanged) continue;

            changed.add(quotation.getId());
            if (dryRun) continue;

            quotation.setFirstYearTotalExTax(pricing.getFirstYearTotalExTax());
            quotation.setFirstYearTotalInclTax(pricing.getFirstYearTotalInclTax());
            quotation.setSubsequentYearTotalExTax(pricing.getSubsequentYearTotalExTax());
            quotation.setSubsequentYearTotalInclTax(pricing.getSubsequentYearTotalInclTax());
            quotation.setGrossMarginRate(pricing.getGrossMarginRate());
            quotation.setDiscountOverride(pricing.isDiscountOverride() ? 1 : 0);
            quotation.setUpdatedAt(LocalDateTime.now());
            quotationMapper.updateById(quotation);

            for (QuotationLineItem line : lines) {
                lineItemMapper.updateById(line);
            }
        }
        log.info("Quotation reprice {}: {} 张报价单金额发生变化，共扫描 {} 张",
                dryRun ? "preview" : "applied", changed.size(), targets.size());
        return changed;
    }

    private static boolean differ(java.math.BigDecimal a, java.math.BigDecimal b) {
        if (a == null && b == null) return false;
        if (a == null || b == null) return true;
        return a.compareTo(b) != 0;
    }
}
