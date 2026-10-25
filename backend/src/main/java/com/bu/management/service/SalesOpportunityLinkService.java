package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Quotation;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.mapper.QuotationMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.bu.management.vo.QuotationPricingResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 报价 → 商机 的单向联动。
 *
 * <p>旧实现只在商机金额为空时写一次首年未税价，阶段和概率完全不动，报价与漏斗割裂。
 * 这里把联动收敛到一处：新建/更新报价回写金额与计数，发送推进阶段，成交锁定采纳的报价单。
 */
@Service
@RequiredArgsConstructor
public class SalesOpportunityLinkService {

    private static final List<String> STAGE_ORDER = List.of(
            "初步接触", "需求确认", "方案报价", "商务谈判", "已成交");

    private final SalesOpportunityMapper opportunityMapper;
    private final QuotationMapper quotationMapper;

    public void onQuotationCreated(Quotation quotation, QuotationPricingResult pricing) {
        if (quotation.getOpportunityId() == null) return;
        SalesOpportunity opportunity = opportunityMapper.selectById(quotation.getOpportunityId());
        if (opportunity == null) return;

        opportunity.setLastQuotationAt(LocalDateTime.now());
        int count = opportunity.getQuotationCount() != null ? opportunity.getQuotationCount() : 0;
        opportunity.setQuotationCount(count + 1);
        opportunity.setQuotedAmount(quotation.getFirstYearTotalInclTax());
        if (pricing != null && pricing.getGrossMarginRate() != null) {
            opportunity.setGrossMarginRate(pricing.getGrossMarginRate());
        }
        opportunityMapper.updateById(opportunity);
    }

    public void onQuotationUpdated(Quotation quotation, QuotationPricingResult pricing) {
        if (quotation.getOpportunityId() == null) return;
        SalesOpportunity opportunity = opportunityMapper.selectById(quotation.getOpportunityId());
        if (opportunity == null) return;

        // 只有首选方案或该商机唯一的报价才覆盖商机金额
        boolean preferred = Integer.valueOf(1).equals(quotation.getIsPreferred());
        long total = quotationMapper.selectCount(new LambdaQueryWrapper<Quotation>()
                .eq(Quotation::getOpportunityId, quotation.getOpportunityId()));
        if (preferred || total <= 1) {
            opportunity.setQuotedAmount(quotation.getFirstYearTotalInclTax());
            if (pricing != null && pricing.getGrossMarginRate() != null) {
                opportunity.setGrossMarginRate(pricing.getGrossMarginRate());
            }
            opportunity.setLastQuotationAt(LocalDateTime.now());
            opportunityMapper.updateById(opportunity);
        }
    }

    public void onQuotationStatusChanged(Quotation quotation) {
        if (quotation.getOpportunityId() == null) return;
        SalesOpportunity opportunity = opportunityMapper.selectById(quotation.getOpportunityId());
        if (opportunity == null) return;

        String stage = quotation.getStatus();
        if ("SENT".equals(stage)) {
            advanceStage(opportunity, "方案报价");
            if (quotation.getFirstYearTotalInclTax() != null) {
                opportunity.setQuotedAmount(quotation.getFirstYearTotalInclTax());
            }
        } else if ("APPROVED".equals(stage)) {
            advanceStage(opportunity, "方案报价");
        } else if ("ACCEPTED".equals(stage)) {
            opportunity.setStatus("已成交");
            opportunity.setProbability(100);
            opportunity.setWonQuotationId(quotation.getId());
            if (quotation.getFirstYearTotalInclTax() != null) {
                opportunity.setAmount(quotation.getFirstYearTotalInclTax());
                opportunity.setQuotedAmount(quotation.getFirstYearTotalInclTax());
            }
            if (quotation.getGrossMarginRate() != null) {
                opportunity.setGrossMarginRate(quotation.getGrossMarginRate());
            }
        } else if ("REJECTED".equals(stage)) {
            if (opportunity.getWonQuotationId() != null
                    && opportunity.getWonQuotationId().equals(quotation.getId())) {
                opportunity.setWonQuotationId(null);
            }
        }
        opportunityMapper.updateById(opportunity);
    }

    public void onPreferredChanged(Quotation quotation) {
        if (quotation.getOpportunityId() == null) return;
        SalesOpportunity opportunity = opportunityMapper.selectById(quotation.getOpportunityId());
        if (opportunity == null) return;
        opportunity.setQuotedAmount(quotation.getFirstYearTotalInclTax());
        opportunity.setGrossMarginRate(quotation.getGrossMarginRate());
        opportunityMapper.updateById(opportunity);
    }

    public void onQuotationDeleted(Quotation quotation) {
        if (quotation.getOpportunityId() == null) return;
        SalesOpportunity opportunity = opportunityMapper.selectById(quotation.getOpportunityId());
        if (opportunity == null) return;
        int count = opportunity.getQuotationCount() != null ? opportunity.getQuotationCount() : 1;
        opportunity.setQuotationCount(Math.max(0, count - 1));
        opportunityMapper.updateById(opportunity);
    }

    /** 只会向前推进，不会因为一张报价而把商机阶段拉回去。 */
    private void advanceStage(SalesOpportunity opportunity, String target) {
        String current = opportunity.getStatus();
        int currentIdx = STAGE_ORDER.indexOf(current);
        int targetIdx = STAGE_ORDER.indexOf(target);
        if (currentIdx >= 0 && targetIdx > currentIdx) {
            opportunity.setStatus(target);
            Integer probability = opportunity.getProbability();
            if (probability == null || probability < 50) {
                opportunity.setProbability(Math.max(probability != null ? probability : 0, 50));
            }
        }
    }

    public BigDecimal quotedAmountOf(SalesOpportunity opportunity) {
        return opportunity.getQuotedAmount();
    }
}
