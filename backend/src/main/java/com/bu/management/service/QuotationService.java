package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.QuotationGenerateRequest;
import com.bu.management.dto.QuotationUpdateRequest;
import com.bu.management.entity.*;
import com.bu.management.mapper.*;
import com.bu.management.vo.QuotationListVO;
import com.bu.management.vo.QuotationPricingResult;
import com.bu.management.vo.QuotationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
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
    private final QuotationVariantMapper variantMapper;
    private final QuotationPricingService pricingService;
    private final QuotationWorkflowService workflowService;
    private final SalesOpportunityLinkService opportunityLink;

    private static final List<String> STATUS_FLOW = List.of("DRAFT", "INTERNAL_REVIEW", "APPROVED", "SENT");

    @Transactional(rollbackFor = Exception.class)
    public QuotationVO generateQuotation(QuotationGenerateRequest request) {
        QuotationPolicy policy = policyMapper.selectById(request.getPolicyId());
        if (policy == null) throw new RuntimeException("报价策略不存在");
        if (!"PUBLISHED".equals(policy.getStatus())) throw new RuntimeException("策略未发布，无法生成报价");

        Quotation quotation = new Quotation();
        quotation.setQuotationNo(generateQuotationNo());
        quotation.setPolicyId(policy.getId());
        quotation.setTemplateId(request.getTemplateId() != null
                ? request.getTemplateId() : policy.getTemplateId());
        quotation.setTaxMode(policy.getTaxMode());
        quotation.setQuoteDate(request.getQuoteDate() != null ? request.getQuoteDate() : LocalDate.now());
        quotation.setValidityDays(request.getValidityDays() != null ? request.getValidityDays() : 30);
        quotation.setCurrency("CNY");
        quotation.setInvoiceType("增值税专用发票");
        quotation.setStatus("DRAFT");
        quotation.setVersionNo(1);
        quotation.setIsPreferred(0);
        quotation.setDiscountOverride(0);
        quotation.setCreatedAt(LocalDateTime.now());
        quotation.setUpdatedAt(LocalDateTime.now());

        if (StringUtils.hasText(request.getCustomerName())) {
            quotation.setCustomerName(request.getCustomerName());
        }
        quotation.setContactPerson(request.getContactPerson());
        quotation.setContactPhone(request.getContactPhone());
        quotation.setContactEmail(request.getContactEmail());
        quotation.setDeliveryPeriod(request.getDeliveryPeriod());

        if (request.getOpportunityId() != null) {
            SalesOpportunity opportunity = opportunityMapper.selectById(request.getOpportunityId());
            if (opportunity == null) throw new RuntimeException("商机不存在");
            quotation.setOpportunityId(request.getOpportunityId());
            if (!StringUtils.hasText(quotation.getCustomerName())) {
                quotation.setCustomerName(opportunity.getCustomer());
            }
        }

        quotationMapper.insert(quotation);

        List<QuotationLineItem> lineItems = pricingService.buildLinesFromPolicy(policy.getId());
        applyOverrides(lineItems, request.getLineItemOverrides());
        QuotationPricingResult pricing = recalculate(quotation, lineItems, policy);
        snapshot(quotation, policy, lineItems);

        if (request.getOpportunityId() != null) {
            QuotationVariant variant = resolveVariant(request.getOpportunityId(), quotation.getId(),
                    request.getVariantName(), quotation.getCreatedBy());
            quotation.setVariantId(variant.getId());
            quotationMapper.updateById(quotation);
        }

        persistLines(quotation.getId(), lineItems);
        applyReviewRequired(quotation, policy, pricing);
        quotationMapper.updateById(quotation);

        opportunityLink.onQuotationCreated(quotation, pricing);
        workflowService.log(quotation.getId(), null, "DRAFT", "CREATE", quotation.getCreatedBy(), null);
        return toVO(quotation, lineItems, null);
    }

    /** 编辑草稿：客户信息 + 行级覆盖（勾选/数量/单价/折扣/自定义行/删除），全部经定价引擎重算。 */
    @Transactional(rollbackFor = Exception.class)
    public QuotationVO updateQuotation(Long id, QuotationUpdateRequest request) {
        Quotation quotation = ensureQuotationExists(id);
        if (!"DRAFT".equals(quotation.getStatus()) && !"INTERNAL_REVIEW".equals(quotation.getStatus())) {
            throw new RuntimeException("仅草稿或审核中状态可编辑");
        }

        if (StringUtils.hasText(request.getCustomerName())) quotation.setCustomerName(request.getCustomerName());
        quotation.setContactPerson(request.getContactPerson());
        quotation.setContactPhone(request.getContactPhone());
        quotation.setContactEmail(request.getContactEmail());
        quotation.setDeliveryPeriod(request.getDeliveryPeriod());
        if (request.getQuoteDate() != null) quotation.setQuoteDate(request.getQuoteDate());
        if (request.getValidityDays() != null) quotation.setValidityDays(request.getValidityDays());
        if (request.getQuotationNote() != null) quotation.setQuotationNote(request.getQuotationNote());

        List<QuotationLineItem> lineItems = loadLineItems(id);
        if (request.getLineItemOverrides() != null) {
            applyOverrides(lineItems, request.getLineItemOverrides());
        }

        QuotationPolicy policy = quotation.getPolicyId() != null ? policyMapper.selectById(quotation.getPolicyId()) : null;
        QuotationPricingResult pricing = recalculate(quotation, lineItems, policy);

        lineItemMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuotationLineItem>()
                .eq(QuotationLineItem::getQuotationId, id));
        persistLines(id, lineItems);
        applyReviewRequired(quotation, policy, pricing);
        quotationMapper.updateById(quotation);

        opportunityLink.onQuotationUpdated(quotation, pricing);
        return toVO(quotation, loadLineItems(id), loadBrandScopes(id));
    }

    /** 试算：不落库，供前端报价编辑器实时汇总，杜绝前后端两套算法分叉。 */
    public QuotationPricingResult preview(Long policyId, List<QuotationGenerateRequest.LineItemOverride> overrides,
                                         Long quotationId) {
        QuotationPolicy policy = policyMapper.selectById(policyId);
        if (policy == null) throw new RuntimeException("报价策略不存在");

        List<QuotationLineItem> lineItems;
        if (quotationId != null) {
            lineItems = loadLineItems(quotationId);
        } else {
            lineItems = pricingService.buildLinesFromPolicy(policyId);
        }
        if (overrides != null) applyOverrides(lineItems, overrides);

        Quotation staging = new Quotation();
        staging.setPolicyId(policyId);
        staging.setTaxMode(policy.getTaxMode());
        return recalculate(staging, lineItems, policy);
    }

    public QuotationVO getQuotationDetail(Long id) {
        Quotation quotation = ensureQuotationExists(id);
        return toVO(quotation, loadLineItems(id), loadBrandScopes(id));
    }

    public Page<QuotationListVO> listQuotations(String keyword, String status, String customerName,
                                                Long opportunityId, Boolean dueSoon, int page, int size) {
        LambdaQueryWrapper<Quotation> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            query.and(w -> w.like(Quotation::getQuotationNo, keyword).or().like(Quotation::getCustomerName, keyword));
        }
        query.eq(StringUtils.hasText(status), Quotation::getStatus, status)
                .eq(StringUtils.hasText(customerName), Quotation::getCustomerName, customerName)
                .eq(opportunityId != null, Quotation::getOpportunityId, opportunityId)
                .orderByDesc(Quotation::getCreatedAt);

        Page<Quotation> pageResult = quotationMapper.selectPage(new Page<>(page, size), query);

        List<QuotationListVO> vos = pageResult.getRecords().stream()
                .map(this::toListVO)
                .filter(vo -> dueSoon == null || !dueSoon || Boolean.TRUE.equals(vo.getDueSoon()))
                .collect(Collectors.toList());

        Page<QuotationListVO> result = new Page<>(page, size);
        result.setRecords(vos);
        result.setTotal(pageResult.getTotal());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, String status) {
        workflowService.transition(id, status, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteQuotation(Long id) {
        Quotation quotation = ensureQuotationExists(id);
        if (!"DRAFT".equals(quotation.getStatus())) throw new RuntimeException("仅草稿状态可删除");
        if (Long.valueOf(1).equals(quotation.getIsPreferred())) throw new RuntimeException("首选方案不可直接删除，请先取消首选");

        lineItemMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuotationLineItem>()
                .eq(QuotationLineItem::getQuotationId, id));
        brandScopeMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuotationBrandScope>()
                .eq(QuotationBrandScope::getQuotationId, id));
        quotationMapper.deleteById(id);
        opportunityLink.onQuotationDeleted(quotation);
    }

    public List<QuotationListVO> listQuotationsByOpportunity(Long opportunityId) {
        LambdaQueryWrapper<Quotation> query = new LambdaQueryWrapper<>();
        query.eq(Quotation::getOpportunityId, opportunityId)
                .orderByDesc(Quotation::getCreatedAt);
        return quotationMapper.selectList(query).stream().map(this::toListVO).collect(Collectors.toList());
    }

    /** 基于现有报价单复制出同方案的新版本（versionNo + 1），旧版本保留可追溯。 */
    @Transactional(rollbackFor = Exception.class)
    public QuotationVO createVersion(Long quotationId) {
        Quotation source = ensureQuotationExists(quotationId);
        Quotation copy = new Quotation();
        copy.setQuotationNo(generateQuotationNo());
        copy.setPolicyId(source.getPolicyId());
        copy.setTemplateId(source.getTemplateId());
        copy.setOpportunityId(source.getOpportunityId());
        copy.setVariantId(source.getVariantId());
        copy.setBaseQuotationId(source.getId());
        copy.setVersionNo((source.getVersionNo() != null ? source.getVersionNo() : 1) + 1);
        copy.setTaxMode(source.getTaxMode());
        copy.setCustomerName(source.getCustomerName());
        copy.setContactPerson(source.getContactPerson());
        copy.setContactPhone(source.getContactPhone());
        copy.setContactEmail(source.getContactEmail());
        copy.setDeliveryPeriod(source.getDeliveryPeriod());
        copy.setQuoteDate(LocalDate.now());
        copy.setValidityDays(source.getValidityDays());
        copy.setCurrency(source.getCurrency());
        copy.setInvoiceType(source.getInvoiceType());
        copy.setQuotationNote(source.getQuotationNote());
        copy.setStatus("DRAFT");
        copy.setIsPreferred(0);
        copy.setDiscountOverride(source.getDiscountOverride());
        copy.setGrossMarginRate(source.getGrossMarginRate());
        copy.setSnapshotJson(source.getSnapshotJson());
        copy.setCreatedAt(LocalDateTime.now());
        copy.setUpdatedAt(LocalDateTime.now());
        quotationMapper.insert(copy);

        List<QuotationLineItem> copies = loadLineItems(quotationId).stream().map(li -> {
            QuotationLineItem n = copyLine(li);
            n.setQuotationId(copy.getId());
            return n;
        }).collect(Collectors.toList());
        persistLines(copy.getId(), copies);
        for (QuotationBrandScope scope : loadBrandScopes(quotationId)) {
            scope.setId(null);
            scope.setQuotationId(copy.getId());
            brandScopeMapper.insert(scope);
        }

        if (copy.getVariantId() != null) {
            QuotationVariant variant = variantMapper.selectById(copy.getVariantId());
            if (variant != null) {
                variant.setLatestQuotationId(copy.getId());
                variant.setUpdatedAt(LocalDateTime.now());
                variantMapper.updateById(variant);
            }
        }
        opportunityLink.onQuotationCreated(copy, null);
        workflowService.log(copy.getId(), null, "DRAFT", "VERSION", source.getCreatedBy(), null);
        return toVO(copy, copies, loadBrandScopes(copy.getId()));
    }

    /** 在同一商机下新建独立方案（可与已有方案并存，用于 A/B 报价）。 */
    @Transactional(rollbackFor = Exception.class)
    public QuotationVO createVariant(Long quotationId, String name, String description) {
        Quotation source = ensureQuotationExists(quotationId);
        if (source.getOpportunityId() == null) throw new RuntimeException("仅关联商机的报价单可拆分方案");

        QuotationVariant variant = new QuotationVariant();
        variant.setOpportunityId(source.getOpportunityId());
        variant.setName(StringUtils.hasText(name) ? name : "方案 " + LocalDate.now().format(DateTimeFormatter.ofPattern("MMdd")));
        variant.setDescription(description);
        variant.setVariantStatus("ACTIVE");
        variant.setCreatedBy(source.getCreatedBy());
        variant.setCreatedAt(LocalDateTime.now());
        variant.setUpdatedAt(LocalDateTime.now());
        variantMapper.insert(variant);

        Quotation copy = copyQuotation(source, generateQuotationNo());
        copy.setVariantId(variant.getId());
        copy.setVersionNo(1);
        copy.setBaseQuotationId(null);
        quotationMapper.insert(copy);
        variant.setLatestQuotationId(copy.getId());
        variantMapper.updateById(variant);

        List<QuotationLineItem> copies = loadLineItems(quotationId).stream().map(li -> {
            QuotationLineItem n = copyLine(li);
            n.setQuotationId(copy.getId());
            return n;
        }).collect(Collectors.toList());
        persistLines(copy.getId(), copies);

        opportunityLink.onQuotationCreated(copy, null);
        return toVO(copy, copies, null);
    }

    public List<QuotationVariant> listVariants(Long opportunityId) {
        LambdaQueryWrapper<QuotationVariant> query = new LambdaQueryWrapper<>();
        query.eq(QuotationVariant::getOpportunityId, opportunityId)
                .orderByAsc(QuotationVariant::getCreatedAt);
        return variantMapper.selectList(query);
    }

    /** 商机内只能有一个首选方案。 */
    @Transactional(rollbackFor = Exception.class)
    public void setPreferred(Long quotationId) {
        Quotation quotation = ensureQuotationExists(quotationId);
        if (quotation.getOpportunityId() == null) throw new RuntimeException("未关联商机的报价单不可设为首选");

        LambdaQueryWrapper<Quotation> query = new LambdaQueryWrapper<>();
        query.eq(Quotation::getOpportunityId, quotation.getOpportunityId())
                .eq(Quotation::getIsPreferred, 1);
        for (Quotation other : quotationMapper.selectList(query)) {
            if (!other.getId().equals(quotationId)) {
                other.setIsPreferred(0);
                other.setPreferredAt(null);
                quotationMapper.updateById(other);
            }
        }
        quotation.setIsPreferred(1);
        quotation.setPreferredAt(LocalDateTime.now());
        quotationMapper.updateById(quotation);
        opportunityLink.onPreferredChanged(quotation);
    }

    @Transactional(rollbackFor = Exception.class)
    public void replaceBrandScopes(Long quotationId, List<QuotationBrandScope> scopes) {
        ensureQuotationExists(quotationId);
        brandScopeMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuotationBrandScope>()
                .eq(QuotationBrandScope::getQuotationId, quotationId));
        if (scopes == null) return;
        int sort = 0;
        for (QuotationBrandScope scope : scopes) {
            scope.setId(null);
            scope.setQuotationId(quotationId);
            scope.setSortOrder(sort++);
            brandScopeMapper.insert(scope);
        }
    }

    // ========== 内部 ==========

    private void applyOverrides(List<QuotationLineItem> lineItems,
                                List<QuotationGenerateRequest.LineItemOverride> overrides) {
        if (overrides == null || overrides.isEmpty()) return;

        Map<Long, QuotationLineItem> byPolicyItem = new LinkedHashMap<>();
        Map<Long, QuotationLineItem> byLineItem = new LinkedHashMap<>();
        for (QuotationLineItem li : lineItems) {
            if (li.getPolicyItemId() != null) byPolicyItem.put(li.getPolicyItemId(), li);
            if (li.getId() != null) byLineItem.put(li.getId(), li);
        }

        List<QuotationLineItem> removed = new ArrayList<>();
        for (QuotationGenerateRequest.LineItemOverride o : overrides) {
            QuotationLineItem li = o.getLineItemId() != null ? byLineItem.get(o.getLineItemId())
                    : byPolicyItem.get(o.getPolicyItemId());

            if (li == null) {
                if (o.getIsCustom() != null && o.getIsCustom()) {
                    lineItems.add(customLine(o, lineItems.size()));
                }
                continue;
            }
            if (Boolean.TRUE.equals(o.getRemove())) {
                if (li.getIsCustom() != null && li.getIsCustom() == 1) removed.add(li);
                continue;
            }
            if (o.getIsSelected() != null) li.setIsSelected(o.getIsSelected() ? 1 : 0);
            if (o.getQuantity() != null) li.setQuantity(o.getQuantity());
            if (o.getDiscountRate() != null) li.setDiscountRate(o.getDiscountRate());
            if (o.getUnitPriceExTax() != null && li.getUnitPriceExTax() == null
                    || (o.getUnitPriceExTax() != null && o.getUnitPriceExTax().compareTo(
                    li.getUnitPriceExTax() != null ? li.getUnitPriceExTax() : BigDecimal.ZERO) != 0)) {
                li.setUnitPriceExTax(o.getUnitPriceExTax());
                li.setPriceOverrideReason(o.getPriceOverrideReason());
            }
            if (o.getCostPriceExTax() != null) li.setCostPriceExTax(o.getCostPriceExTax());
            if (o.getSortOrder() != null) li.setSortOrder(o.getSortOrder());
            if (StringUtils.hasText(o.getItemName())) li.setItemName(o.getItemName());
            if (o.getDescription() != null) li.setDescription(o.getDescription());
            if (o.getIsFirstYearFree() != null) li.setIsFirstYearFree(o.getIsFirstYearFree() ? 1 : 0);
        }
        lineItems.removeAll(removed);
    }

    private QuotationLineItem customLine(QuotationGenerateRequest.LineItemOverride o, int sortOrder) {
        QuotationLineItem li = new QuotationLineItem();
        li.setSection(StringUtils.hasText(o.getSection()) ? o.getSection() : "其他");
        li.setSectionCode(QuotationPricingService.normalizeCode(li.getSection()));
        li.setItemName(StringUtils.hasText(o.getItemName()) ? o.getItemName() : "自定义项");
        li.setDescription(o.getDescription());
        li.setIsCustom(1);
        li.setIsSelected(1);
        li.setIsFirstYearFree(Boolean.TRUE.equals(o.getIsFirstYearFree()) ? 1 : 0);
        li.setQuantity(o.getQuantity() != null ? o.getQuantity() : BigDecimal.ONE);
        li.setDiscountRate(o.getDiscountRate() != null ? o.getDiscountRate() : BigDecimal.ONE);
        li.setUnitPriceExTax(o.getUnitPriceExTax() != null ? o.getUnitPriceExTax() : BigDecimal.ZERO);
        li.setCostPriceExTax(o.getCostPriceExTax());
        li.setTaxRate(o.getTaxRate() != null ? o.getTaxRate() : new BigDecimal("0.06"));
        li.setChargeMethod(QuotationPricingService.CHARGE_FIXED);
        li.setPriceOverrideReason(o.getPriceOverrideReason());
        li.setSortOrder(o.getSortOrder() != null ? o.getSortOrder() : sortOrder);
        return li;
    }

    private QuotationPricingResult recalculate(Quotation quotation, List<QuotationLineItem> lineItems,
                                               QuotationPolicy policy) {
        BigDecimal floor = policy != null ? policy.getMinDiscountRate() : null;
        QuotationPricingResult pricing = pricingService.calculate(lineItems, floor);
        quotation.setFirstYearTotalExTax(pricing.getFirstYearTotalExTax());
        quotation.setFirstYearTotalInclTax(pricing.getFirstYearTotalInclTax());
        quotation.setSubsequentYearTotalExTax(pricing.getSubsequentYearTotalExTax());
        quotation.setSubsequentYearTotalInclTax(pricing.getSubsequentYearTotalInclTax());
        quotation.setGrossMarginRate(pricing.getGrossMarginRate());
        quotation.setDiscountOverride(pricing.isDiscountOverride() ? 1 : 0);
        return pricing;
    }

    private void applyReviewRequired(Quotation quotation, QuotationPolicy policy, QuotationPricingResult pricing) {
        boolean needsApproval = pricing.isDiscountOverride()
                || (policy != null && (policy.getApprovalRequired() == null || policy.getApprovalRequired() == 1));
        if (needsApproval && "DRAFT".equals(quotation.getStatus())) {
            quotation.setQuotationNote(buildAutoNote(quotation));
        }
    }

    private String buildAutoNote(Quotation quotation) {
        return String.format("报价有效期 %d 天，逾期作废；价格含税口径：%s。",
                quotation.getValidityDays() != null ? quotation.getValidityDays() : 30,
                "TAX_INCLUDED".equals(quotation.getTaxMode()) ? "含税" : "未税");
    }

    private void snapshot(Quotation quotation, QuotationPolicy policy, List<QuotationLineItem> lineItems) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"policyName\":\"").append(escape(policy.getName())).append("\",");
        sb.append("\"policyType\":\"").append(escape(policy.getType())).append("\",");
        sb.append("\"policyVersion\":").append(policy.getVersion() != null ? policy.getVersion() : 1).append(",");
        sb.append("\"minDiscountRate\":").append(policy.getMinDiscountRate() != null ? policy.getMinDiscountRate() : "null").append(",");
        sb.append("\"lineItemCount\":").append(lineItems.size()).append(",");
        sb.append("\"capturedAt\":\"").append(LocalDateTime.now()).append("\"");
        sb.append("}");
        quotation.setSnapshotJson(sb.toString());
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private QuotationVariant resolveVariant(Long opportunityId, Long quotationId, String name, String operator) {
        QuotationVariant variant = new QuotationVariant();
        variant.setOpportunityId(opportunityId);
        variant.setName(StringUtils.hasText(name) ? name : "默认方案");
        variant.setLatestQuotationId(quotationId);
        variant.setVariantStatus("ACTIVE");
        variant.setCreatedBy(operator);
        variant.setCreatedAt(LocalDateTime.now());
        variant.setUpdatedAt(LocalDateTime.now());
        variantMapper.insert(variant);
        return variant;
    }

    private void persistLines(Long quotationId, List<QuotationLineItem> lineItems) {
        int sort = 0;
        for (QuotationLineItem li : lineItems) {
            li.setQuotationId(quotationId);
            if (li.getSortOrder() == null) li.setSortOrder(sort);
            sort++;
            if (li.getId() == null || li.getId() == 0) {
                lineItemMapper.insert(li);
            } else {
                lineItemMapper.updateById(li);
            }
        }
    }

    private Quotation copyQuotation(Quotation source, String newNo) {
        Quotation copy = new Quotation();
        copy.setQuotationNo(newNo);
        copy.setPolicyId(source.getPolicyId());
        copy.setTemplateId(source.getTemplateId());
        copy.setOpportunityId(source.getOpportunityId());
        copy.setTaxMode(source.getTaxMode());
        copy.setCustomerName(source.getCustomerName());
        copy.setContactPerson(source.getContactPerson());
        copy.setContactPhone(source.getContactPhone());
        copy.setContactEmail(source.getContactEmail());
        copy.setDeliveryPeriod(source.getDeliveryPeriod());
        copy.setQuoteDate(LocalDate.now());
        copy.setValidityDays(source.getValidityDays());
        copy.setCurrency(source.getCurrency());
        copy.setInvoiceType(source.getInvoiceType());
        copy.setQuotationNote(source.getQuotationNote());
        copy.setStatus("DRAFT");
        copy.setIsPreferred(0);
        copy.setGrossMarginRate(source.getGrossMarginRate());
        copy.setSnapshotJson(source.getSnapshotJson());
        copy.setCreatedAt(LocalDateTime.now());
        copy.setUpdatedAt(LocalDateTime.now());
        return copy;
    }

    private QuotationLineItem copyLine(QuotationLineItem li) {
        QuotationLineItem n = new QuotationLineItem();
        n.setPolicyItemId(li.getPolicyItemId());
        n.setSection(li.getSection());
        n.setSectionCode(li.getSectionCode());
        n.setCategory(li.getCategory());
        n.setItemName(li.getItemName());
        n.setDescription(li.getDescription());
        n.setPriceDescription(li.getPriceDescription());
        n.setIsCustom(li.getIsCustom());
        n.setIsFirstYearFree(li.getIsFirstYearFree());
        n.setPriceOverrideReason(li.getPriceOverrideReason());
        n.setIsSelected(li.getIsSelected());
        n.setQuantity(li.getQuantity());
        n.setUnitPriceExTax(li.getUnitPriceExTax());
        n.setCostPriceExTax(li.getCostPriceExTax());
        n.setTaxRate(li.getTaxRate());
        n.setDiscountRate(li.getDiscountRate());
        n.setSubtotalExTax(li.getSubtotalExTax());
        n.setSubtotalInclTax(li.getSubtotalInclTax());
        n.setChargeMethod(li.getChargeMethod());
        n.setRemark(li.getRemark());
        n.setSortOrder(li.getSortOrder());
        return n;
    }

    private List<QuotationLineItem> loadLineItems(Long quotationId) {
        LambdaQueryWrapper<QuotationLineItem> query = new LambdaQueryWrapper<>();
        query.eq(QuotationLineItem::getQuotationId, quotationId)
                .orderByAsc(QuotationLineItem::getSortOrder);
        return lineItemMapper.selectList(query);
    }

    private List<QuotationBrandScope> loadBrandScopes(Long quotationId) {
        LambdaQueryWrapper<QuotationBrandScope> query = new LambdaQueryWrapper<>();
        query.eq(QuotationBrandScope::getQuotationId, quotationId)
                .orderByAsc(QuotationBrandScope::getSortOrder);
        return brandScopeMapper.selectList(query);
    }

    private String generateQuotationNo() {
        String prefix = "QT-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        LambdaQueryWrapper<Quotation> query = new LambdaQueryWrapper<>();
        query.likeRight(Quotation::getQuotationNo, prefix)
                .orderByDesc(Quotation::getQuotationNo)
                .last("LIMIT 1");
        Quotation last = quotationMapper.selectOne(query);
        int seq = 1;
        if (last != null && last.getQuotationNo() != null && last.getQuotationNo().length() == prefix.length() + 3) {
            try {
                seq = Integer.parseInt(last.getQuotationNo().substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
            }
        }
        return prefix + String.format("%03d", seq);
    }

    private QuotationListVO toListVO(Quotation q) {
        QuotationListVO vo = new QuotationListVO();
        vo.setId(q.getId());
        vo.setQuotationNo(q.getQuotationNo());
        vo.setCustomerName(q.getCustomerName());
        vo.setFirstYearTotalInclTax(q.getFirstYearTotalInclTax());
        vo.setFirstYearTotalExTax(q.getFirstYearTotalExTax());
        vo.setStatus(q.getStatus());
        vo.setQuoteDate(q.getQuoteDate());
        vo.setOpportunityId(q.getOpportunityId());
        vo.setVariantId(q.getVariantId());
        vo.setVersionNo(q.getVersionNo());
        vo.setIsPreferred(q.getIsPreferred());
        vo.setGrossMarginRate(q.getGrossMarginRate());
        vo.setDiscountOverride(q.getDiscountOverride());
        vo.setValidityDays(q.getValidityDays());
        if (q.getQuoteDate() != null && q.getValidityDays() != null) {
            LocalDate expireAt = q.getQuoteDate().plusDays(q.getValidityDays());
            vo.setExpireAt(expireAt);
            vo.setDueSoon(!expireAt.isBefore(LocalDate.now()) && !expireAt.isAfter(LocalDate.now().plusDays(7)));
        }
        if (q.getOpportunityId() != null) {
            SalesOpportunity opp = opportunityMapper.selectById(q.getOpportunityId());
            if (opp != null) vo.setOpportunityName(opp.getName());
        }
        if (q.getVariantId() != null) {
            QuotationVariant variant = variantMapper.selectById(q.getVariantId());
            if (variant != null) vo.setVariantName(variant.getName());
        }
        return vo;
    }

    private QuotationVO toVO(Quotation quotation, List<QuotationLineItem> lineItems, List<QuotationBrandScope> brandScopes) {
        QuotationVO vo = new QuotationVO();
        vo.setId(quotation.getId());
        vo.setQuotationNo(quotation.getQuotationNo());
        vo.setPolicyId(quotation.getPolicyId());
        vo.setTemplateId(quotation.getTemplateId());
        vo.setOpportunityId(quotation.getOpportunityId());
        vo.setVariantId(quotation.getVariantId());
        vo.setVersionNo(quotation.getVersionNo());
        vo.setBaseQuotationId(quotation.getBaseQuotationId());
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
        vo.setIsPreferred(quotation.getIsPreferred());
        vo.setPreferredAt(quotation.getPreferredAt());
        vo.setDiscountOverride(quotation.getDiscountOverride());
        vo.setGrossMarginRate(quotation.getGrossMarginRate());
        vo.setSnapshotJson(quotation.getSnapshotJson());
        vo.setBoundContractNo(quotation.getBoundContractNo());
        vo.setCreatedBy(quotation.getCreatedBy());
        vo.setCreatedAt(quotation.getCreatedAt());
        vo.setUpdatedAt(quotation.getUpdatedAt());
        vo.setLineItems(lineItems);
        vo.setBrandScopes(brandScopes != null ? brandScopes : loadBrandScopes(quotation.getId()));

        if (quotation.getOpportunityId() != null) {
            SalesOpportunity opp = opportunityMapper.selectById(quotation.getOpportunityId());
            if (opp != null) vo.setOpportunityName(opp.getName());
        }
        if (quotation.getVariantId() != null) {
            QuotationVariant variant = variantMapper.selectById(quotation.getVariantId());
            if (variant != null) vo.setVariantName(variant.getName());
        }
        vo.setAvailableTransitions(workflowService.availableTransitions(quotation));
        return vo;
    }

    private Quotation ensureQuotationExists(Long id) {
        Quotation quotation = quotationMapper.selectById(id);
        if (quotation == null) throw new RuntimeException("报价单不存在");
        return quotation;
    }

    /** 供过期任务使用。 */
    List<Quotation> findExpirable() {
        LambdaQueryWrapper<Quotation> query = new LambdaQueryWrapper<>();
        query.in(Quotation::getStatus, List.of("APPROVED", "SENT"));
        return quotationMapper.selectList(query);
    }
}
