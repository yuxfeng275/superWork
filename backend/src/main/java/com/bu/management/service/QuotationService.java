package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.QuotationGenerateRequest;
import com.bu.management.dto.QuotationUpdateRequest;
import com.bu.management.entity.*;
import com.bu.management.mapper.*;
import com.bu.management.vo.QuotationListVO;
import com.bu.management.vo.QuotationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuotationService {
    private final QuotationMapper quotationMapper;
    private final QuotationLineItemMapper lineItemMapper;
    private final QuotationBrandScopeMapper brandScopeMapper;
    private final QuotationPolicyMapper policyMapper;
    private final QuotationPolicyItemMapper policyItemMapper;
    private final SalesOpportunityMapper opportunityMapper;

    private static final List<String> STATUS_FLOW = List.of("DRAFT", "INTERNAL_REVIEW", "APPROVED", "SENT");

    @Transactional(rollbackFor = Exception.class)
    public QuotationVO generateQuotation(QuotationGenerateRequest request) {
        QuotationPolicy policy = policyMapper.selectById(request.getPolicyId());
        if (policy == null) throw new RuntimeException("报价策略不存在");
        if (!"PUBLISHED".equals(policy.getStatus())) throw new RuntimeException("策略未发布，无法生成报价");

        List<QuotationPolicyItem> policyItems = listPolicyItemsByPolicyId(policy.getId());

        // 构建override map
        Map<Long, QuotationGenerateRequest.LineItemOverride> overrideMap = new HashMap<>();
        if (request.getLineItemOverrides() != null) {
            for (QuotationGenerateRequest.LineItemOverride o : request.getLineItemOverrides()) {
                overrideMap.put(o.getPolicyItemId(), o);
            }
        }

        Quotation quotation = new Quotation();
        quotation.setQuotationNo(generateQuotationNo());
        quotation.setPolicyId(policy.getId());
        quotation.setTaxMode(policy.getTaxMode());
        quotation.setQuoteDate(LocalDate.now());
        quotation.setValidityDays(30);
        quotation.setCurrency("CNY");
        quotation.setInvoiceType("增值税专用发票");
        quotation.setStatus("DRAFT");
        quotation.setCreatedAt(LocalDateTime.now());

        // 客户信息
        if (StringUtils.hasText(request.getCustomerName())) {
            quotation.setCustomerName(request.getCustomerName());
        }
        quotation.setContactPerson(request.getContactPerson());
        quotation.setContactPhone(request.getContactPhone());
        quotation.setContactEmail(request.getContactEmail());
        quotation.setDeliveryPeriod(request.getDeliveryPeriod());

        // 关联商机
        if (request.getOpportunityId() != null) {
            SalesOpportunity opportunity = opportunityMapper.selectById(request.getOpportunityId());
            if (opportunity == null) throw new RuntimeException("商机不存在");
            quotation.setOpportunityId(request.getOpportunityId());
            if (!StringUtils.hasText(quotation.getCustomerName())) {
                quotation.setCustomerName(opportunity.getCustomer());
            }
        }

        quotationMapper.insert(quotation);

        // 生成line items
        List<QuotationLineItem> lineItems = new ArrayList<>();
        int sort = 0;
        for (QuotationPolicyItem pi : policyItems) {
            QuotationLineItem li = new QuotationLineItem();
            li.setQuotationId(quotation.getId());
            li.setPolicyItemId(pi.getId());
            li.setSection(pi.getSection());
            li.setCategory(pi.getCategory());
            li.setItemName(pi.getItemName());
            li.setDescription(pi.getDescription());
            li.setPriceDescription(pi.getPriceDescription());
            li.setUnitPriceExTax(pi.getUnitPrice());
            li.setTaxRate(pi.getTaxRate());
            li.setChargeMethod(pi.getChargeMethod());
            li.setRemark(pi.getRemark());
            li.setSortOrder(sort++);

            QuotationGenerateRequest.LineItemOverride override = overrideMap.get(pi.getId());
            boolean selected = override != null && Boolean.TRUE.equals(override.getIsSelected());
            if (pi.getIsRequired() != null && pi.getIsRequired() == 1) selected = true;

            li.setIsSelected(selected ? 1 : 0);
            li.setQuantity(override != null && override.getQuantity() != null ? override.getQuantity() : BigDecimal.ONE);
            li.setDiscountRate(override != null && override.getDiscountRate() != null ? override.getDiscountRate() : BigDecimal.ONE);

            if (selected && pi.getUnitPrice() != null) {
                // 首年运维服务免费
                if ("PRIVATE_DEPLOYMENT".equals(policy.getType()) && "运维服务".equals(pi.getSection())) {
                    li.setDiscountRate(BigDecimal.ZERO);
                }
                calculateLineItem(li);
            }

            lineItemMapper.insert(li);
            lineItems.add(li);
        }

        // 汇总计算
        calculateQuotationTotals(quotation, lineItems);
        quotationMapper.updateById(quotation);

        // 同步商机金额
        if (request.getOpportunityId() != null && quotation.getFirstYearTotalExTax() != null) {
            SalesOpportunity opportunity = opportunityMapper.selectById(request.getOpportunityId());
            if (opportunity != null && opportunity.getAmount() == null) {
                opportunity.setAmount(quotation.getFirstYearTotalExTax());
                opportunityMapper.updateById(opportunity);
            }
        }

        return toVO(quotation, lineItems, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public QuotationVO updateQuotation(Long id, QuotationUpdateRequest request) {
        Quotation quotation = ensureQuotationExists(id);
        if (!"DRAFT".equals(quotation.getStatus())) throw new RuntimeException("仅草稿状态可编辑");

        if (StringUtils.hasText(request.getCustomerName())) quotation.setCustomerName(request.getCustomerName());
        quotation.setContactPerson(request.getContactPerson());
        quotation.setContactPhone(request.getContactPhone());
        quotation.setContactEmail(request.getContactEmail());
        quotation.setDeliveryPeriod(request.getDeliveryPeriod());

        // 如果有line item overrides，重建line items
        if (request.getLineItemOverrides() != null && !request.getLineItemOverrides().isEmpty()) {
            Map<Long, QuotationGenerateRequest.LineItemOverride> overrideMap = new HashMap<>();
            for (QuotationGenerateRequest.LineItemOverride o : request.getLineItemOverrides()) {
                overrideMap.put(o.getPolicyItemId(), o);
            }

            LambdaQueryWrapper<QuotationLineItem> itemQuery = new LambdaQueryWrapper<>();
            itemQuery.eq(QuotationLineItem::getQuotationId, id);
            List<QuotationLineItem> existingItems = lineItemMapper.selectList(itemQuery);

            QuotationPolicy policy = policyMapper.selectById(quotation.getPolicyId());

            for (QuotationLineItem li : existingItems) {
                QuotationGenerateRequest.LineItemOverride override = overrideMap.get(li.getPolicyItemId());
                if (override != null) {
                    boolean selected = Boolean.TRUE.equals(override.getIsSelected());
                    QuotationPolicyItem pi = policyItemMapper.selectById(li.getPolicyItemId());
                    if (pi != null && pi.getIsRequired() != null && pi.getIsRequired() == 1) selected = true;

                    li.setIsSelected(selected ? 1 : 0);
                    li.setQuantity(override.getQuantity() != null ? override.getQuantity() : li.getQuantity());
                    li.setDiscountRate(override.getDiscountRate() != null ? override.getDiscountRate() : li.getDiscountRate());

                    if (selected && li.getUnitPriceExTax() != null) {
                        if (policy != null && "PRIVATE_DEPLOYMENT".equals(policy.getType()) && "运维服务".equals(li.getSection())) {
                            li.setDiscountRate(BigDecimal.ZERO);
                        }
                        calculateLineItem(li);
                    } else {
                        li.setSubtotalExTax(null);
                        li.setSubtotalInclTax(null);
                    }
                    lineItemMapper.updateById(li);
                }
            }

            // 重新查询以获取最新数据
            List<QuotationLineItem> updatedItems = lineItemMapper.selectList(itemQuery);
            calculateQuotationTotals(quotation, updatedItems);
        }

        quotationMapper.updateById(quotation);

        LambdaQueryWrapper<QuotationLineItem> itemQuery = new LambdaQueryWrapper<>();
        itemQuery.eq(QuotationLineItem::getQuotationId, id).orderByAsc(QuotationLineItem::getSortOrder);
        List<QuotationLineItem> lineItems = lineItemMapper.selectList(itemQuery);

        LambdaQueryWrapper<QuotationBrandScope> scopeQuery = new LambdaQueryWrapper<>();
        scopeQuery.eq(QuotationBrandScope::getQuotationId, id).orderByAsc(QuotationBrandScope::getSortOrder);
        List<QuotationBrandScope> brandScopes = brandScopeMapper.selectList(scopeQuery);

        return toVO(quotation, lineItems, brandScopes);
    }

    public QuotationVO getQuotationDetail(Long id) {
        Quotation quotation = ensureQuotationExists(id);

        LambdaQueryWrapper<QuotationLineItem> itemQuery = new LambdaQueryWrapper<>();
        itemQuery.eq(QuotationLineItem::getQuotationId, id).orderByAsc(QuotationLineItem::getSortOrder);
        List<QuotationLineItem> lineItems = lineItemMapper.selectList(itemQuery);

        LambdaQueryWrapper<QuotationBrandScope> scopeQuery = new LambdaQueryWrapper<>();
        scopeQuery.eq(QuotationBrandScope::getQuotationId, id).orderByAsc(QuotationBrandScope::getSortOrder);
        List<QuotationBrandScope> brandScopes = brandScopeMapper.selectList(scopeQuery);

        return toVO(quotation, lineItems, brandScopes);
    }

    public Page<QuotationListVO> listQuotations(String keyword, String status, String customerName, int page, int size) {
        LambdaQueryWrapper<Quotation> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            query.and(w -> w.like(Quotation::getQuotationNo, keyword).or().like(Quotation::getCustomerName, keyword));
        }
        query.eq(StringUtils.hasText(status), Quotation::getStatus, status)
                .eq(StringUtils.hasText(customerName), Quotation::getCustomerName, customerName)
                .orderByDesc(Quotation::getCreatedAt);

        Page<Quotation> pageResult = quotationMapper.selectPage(new Page<>(page, size), query);

        List<QuotationListVO> vos = pageResult.getRecords().stream().map(q -> {
            QuotationListVO vo = new QuotationListVO();
            vo.setId(q.getId());
            vo.setQuotationNo(q.getQuotationNo());
            vo.setCustomerName(q.getCustomerName());
            vo.setFirstYearTotalInclTax(q.getFirstYearTotalInclTax());
            vo.setStatus(q.getStatus());
            vo.setQuoteDate(q.getQuoteDate());
            if (q.getOpportunityId() != null) {
                SalesOpportunity opp = opportunityMapper.selectById(q.getOpportunityId());
                if (opp != null) vo.setOpportunityName(opp.getName());
            }
            return vo;
        }).collect(Collectors.toList());

        Page<QuotationListVO> result = new Page<>(page, size);
        result.setRecords(vos);
        result.setTotal(pageResult.getTotal());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, String status) {
        Quotation quotation = ensureQuotationExists(id);
        String currentStatus = quotation.getStatus();

        // 正向流转: DRAFT -> INTERNAL_REVIEW -> APPROVED -> SENT
        int currentIdx = STATUS_FLOW.indexOf(currentStatus);
        int targetIdx = STATUS_FLOW.indexOf(status);

        if (targetIdx >= 0 && targetIdx == currentIdx + 1) {
            // 正常正向流转
            quotation.setStatus(status);
            quotationMapper.updateById(quotation);
            return;
        }

        // 终态: ACCEPTED, REJECTED, EXPIRED
        if (List.of("ACCEPTED", "REJECTED", "EXPIRED").contains(status)) {
            if (!"SENT".equals(currentStatus) && !"APPROVED".equals(currentStatus)) {
                throw new RuntimeException("仅已发送或已批准状态的报价单可标记为最终状态");
            }
            quotation.setStatus(status);
            quotationMapper.updateById(quotation);
            return;
        }

        throw new RuntimeException("无效的状态流转: " + currentStatus + " -> " + status);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteQuotation(Long id) {
        Quotation quotation = ensureQuotationExists(id);
        if (!"DRAFT".equals(quotation.getStatus())) throw new RuntimeException("仅草稿状态可删除");

        LambdaQueryWrapper<QuotationLineItem> itemQuery = new LambdaQueryWrapper<>();
        itemQuery.eq(QuotationLineItem::getQuotationId, id);
        lineItemMapper.delete(itemQuery);

        LambdaQueryWrapper<QuotationBrandScope> scopeQuery = new LambdaQueryWrapper<>();
        scopeQuery.eq(QuotationBrandScope::getQuotationId, id);
        brandScopeMapper.delete(scopeQuery);

        quotationMapper.deleteById(id);
    }

    public List<QuotationListVO> listQuotationsByOpportunity(Long opportunityId) {
        LambdaQueryWrapper<Quotation> query = new LambdaQueryWrapper<>();
        query.eq(Quotation::getOpportunityId, opportunityId)
                .orderByDesc(Quotation::getCreatedAt);
        List<Quotation> list = quotationMapper.selectList(query);
        return list.stream().map(q -> {
            QuotationListVO vo = new QuotationListVO();
            vo.setId(q.getId());
            vo.setQuotationNo(q.getQuotationNo());
            vo.setCustomerName(q.getCustomerName());
            vo.setFirstYearTotalInclTax(q.getFirstYearTotalInclTax());
            vo.setStatus(q.getStatus());
            vo.setQuoteDate(q.getQuoteDate());
            return vo;
        }).collect(Collectors.toList());
    }

    // ========== private helpers ==========

    private String generateQuotationNo() {
        String prefix = "QT-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        LambdaQueryWrapper<Quotation> query = new LambdaQueryWrapper<>();
        query.likeRight(Quotation::getQuotationNo, prefix)
                .orderByDesc(Quotation::getQuotationNo)
                .last("LIMIT 1");
        Quotation last = quotationMapper.selectOne(query);
        int seq = 1;
        if (last != null && last.getQuotationNo().length() == prefix.length() + 3) {
            try {
                seq = Integer.parseInt(last.getQuotationNo().substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {}
        }
        return prefix + String.format("%03d", seq);
    }

    private List<QuotationPolicyItem> listPolicyItemsByPolicyId(Long policyId) {
        LambdaQueryWrapper<QuotationPolicyItem> query = new LambdaQueryWrapper<>();
        query.eq(QuotationPolicyItem::getPolicyId, policyId)
                .orderByAsc(QuotationPolicyItem::getSortOrder);
        return policyItemMapper.selectList(query);
    }

    private void calculateLineItem(QuotationLineItem li) {
        BigDecimal qty = li.getQuantity() != null ? li.getQuantity() : BigDecimal.ONE;
        BigDecimal discount = li.getDiscountRate() != null ? li.getDiscountRate() : BigDecimal.ONE;
        BigDecimal unitPrice = li.getUnitPriceExTax() != null ? li.getUnitPriceExTax() : BigDecimal.ZERO;

        BigDecimal subtotalExTax = unitPrice.multiply(qty).multiply(discount).setScale(2, RoundingMode.HALF_UP);
        li.setSubtotalExTax(subtotalExTax);

        BigDecimal taxRate = li.getTaxRate() != null ? li.getTaxRate() : BigDecimal.ZERO;
        li.setSubtotalInclTax(subtotalExTax.multiply(BigDecimal.ONE.add(taxRate)).setScale(2, RoundingMode.HALF_UP));
    }

    private void calculateQuotationTotals(Quotation quotation, List<QuotationLineItem> lineItems) {
        BigDecimal firstYearExTax = BigDecimal.ZERO;
        BigDecimal firstYearInclTax = BigDecimal.ZERO;
        BigDecimal subsequentExTax = BigDecimal.ZERO;
        BigDecimal subsequentInclTax = BigDecimal.ZERO;

        for (QuotationLineItem li : lineItems) {
            if (li.getIsSelected() == null || li.getIsSelected() == 0) continue;
            if (li.getSubtotalExTax() == null) continue;

            // 运维服务：首年discountRate=0 => subtotal=0 => 算入subsequent
            if ("运维服务".equals(li.getSection()) && li.getDiscountRate() != null
                    && li.getDiscountRate().compareTo(BigDecimal.ZERO) == 0) {
                // 首年免费，计入次年
                BigDecimal fullExTax = BigDecimal.ZERO;
                if (li.getUnitPriceExTax() != null) {
                    BigDecimal qty = li.getQuantity() != null ? li.getQuantity() : BigDecimal.ONE;
                    fullExTax = li.getUnitPriceExTax().multiply(qty).setScale(2, RoundingMode.HALF_UP);
                }
                BigDecimal taxRate = li.getTaxRate() != null ? li.getTaxRate() : BigDecimal.ZERO;
                BigDecimal fullInclTax = fullExTax.multiply(BigDecimal.ONE.add(taxRate)).setScale(2, RoundingMode.HALF_UP);
                subsequentExTax = subsequentExTax.add(fullExTax);
                subsequentInclTax = subsequentInclTax.add(fullInclTax);
            } else {
                firstYearExTax = firstYearExTax.add(li.getSubtotalExTax());
                firstYearInclTax = firstYearInclTax.add(li.getSubtotalInclTax());
            }
        }

        quotation.setFirstYearTotalExTax(firstYearExTax);
        quotation.setFirstYearTotalInclTax(firstYearInclTax);
        quotation.setSubsequentYearTotalExTax(subsequentExTax);
        quotation.setSubsequentYearTotalInclTax(subsequentInclTax);
    }

    private QuotationVO toVO(Quotation quotation, List<QuotationLineItem> lineItems, List<QuotationBrandScope> brandScopes) {
        QuotationVO vo = new QuotationVO();
        vo.setId(quotation.getId());
        vo.setQuotationNo(quotation.getQuotationNo());
        vo.setPolicyId(quotation.getPolicyId());
        vo.setOpportunityId(quotation.getOpportunityId());
        vo.setTaxMode(quotation.getTaxMode());
        vo.setCustomerName(quotation.getCustomerName());
        vo.setContactPerson(quotation.getContactPerson());
        vo.setContactPhone(quotation.getContactPhone());
        vo.setContactEmail(quotation.getContactEmail());
        vo.setDeliveryPeriod(quotation.getDeliveryPeriod());
        vo.setQuoteDate(quotation.getQuoteDate());
        vo.setValidityDays(quotation.getValidityDays());
        vo.setCurrency(quotation.getCurrency());
        vo.setInvoiceType(quotation.getInvoiceType());
        vo.setFirstYearTotalExTax(quotation.getFirstYearTotalExTax());
        vo.setFirstYearTotalInclTax(quotation.getFirstYearTotalInclTax());
        vo.setSubsequentYearTotalExTax(quotation.getSubsequentYearTotalExTax());
        vo.setSubsequentYearTotalInclTax(quotation.getSubsequentYearTotalInclTax());
        vo.setQuotationNote(quotation.getQuotationNote());
        vo.setStatus(quotation.getStatus());
        vo.setCreatedBy(quotation.getCreatedBy());
        vo.setCreatedAt(quotation.getCreatedAt());
        vo.setUpdatedAt(quotation.getUpdatedAt());
        vo.setLineItems(lineItems);
        vo.setBrandScopes(brandScopes);

        if (quotation.getOpportunityId() != null) {
            SalesOpportunity opp = opportunityMapper.selectById(quotation.getOpportunityId());
            if (opp != null) vo.setOpportunityName(opp.getName());
        }
        return vo;
    }

    private Quotation ensureQuotationExists(Long id) {
        Quotation quotation = quotationMapper.selectById(id);
        if (quotation == null) throw new RuntimeException("报价单不存在");
        return quotation;
    }
}