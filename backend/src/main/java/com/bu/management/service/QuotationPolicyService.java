package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.QuotationPolicyItemRequest;
import com.bu.management.dto.QuotationPolicyRequest;
import com.bu.management.entity.QuotationPolicy;
import com.bu.management.entity.QuotationPolicyItem;
import com.bu.management.mapper.QuotationPolicyItemMapper;
import com.bu.management.mapper.QuotationPolicyMapper;
import com.bu.management.vo.QuotationPolicyVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QuotationPolicyService {
    private final QuotationPolicyMapper policyMapper;
    private final QuotationPolicyItemMapper itemMapper;

    public List<QuotationPolicy> listPolicies(String type, String taxMode, String status) {
        LambdaQueryWrapper<QuotationPolicy> query = new LambdaQueryWrapper<>();
        query.eq(StringUtils.hasText(type), QuotationPolicy::getType, type)
                .eq(StringUtils.hasText(taxMode), QuotationPolicy::getTaxMode, taxMode)
                .eq(StringUtils.hasText(status), QuotationPolicy::getStatus, status)
                .orderByDesc(QuotationPolicy::getCreatedAt);
        return policyMapper.selectList(query);
    }

    public QuotationPolicyVO getPolicyDetail(Long id) {
        QuotationPolicy policy = ensurePolicyExists(id);
        List<QuotationPolicyItem> items = listPolicyItems(id);
        return toVO(policy, items);
    }

    public QuotationPolicyVO getPublishedPolicy(String type, String taxMode) {
        LambdaQueryWrapper<QuotationPolicy> query = new LambdaQueryWrapper<>();
        query.eq(QuotationPolicy::getType, type)
                .eq(QuotationPolicy::getTaxMode, taxMode)
                .eq(QuotationPolicy::getStatus, "PUBLISHED")
                .last("LIMIT 1");
        QuotationPolicy policy = policyMapper.selectOne(query);
        if (policy == null) return null;
        return getPolicyDetail(policy.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public QuotationPolicy createPolicy(QuotationPolicyRequest request) {
        if (!StringUtils.hasText(request.getName())) throw new RuntimeException("策略名称不能为空");
        if (!StringUtils.hasText(request.getType())) throw new RuntimeException("策略类型不能为空");
        if (!StringUtils.hasText(request.getTaxMode())) throw new RuntimeException("计税模式不能为空");

        QuotationPolicy policy = new QuotationPolicy();
        policy.setName(request.getName());
        policy.setType(request.getType());
        policy.setTaxMode(request.getTaxMode());
        policy.setVersion(1);
        policy.setStatus("DRAFT");
        policy.setEffectiveDate(request.getEffectiveDate());
        policy.setExpiryDate(request.getExpiryDate());
        policy.setCreatedAt(LocalDateTime.now());
        policyMapper.insert(policy);
        return policy;
    }

    @Transactional(rollbackFor = Exception.class)
    public QuotationPolicy updatePolicy(Long id, QuotationPolicyRequest request) {
        QuotationPolicy policy = ensurePolicyExists(id);
        if (!"DRAFT".equals(policy.getStatus())) throw new RuntimeException("仅草稿状态可编辑");

        if (StringUtils.hasText(request.getName())) policy.setName(request.getName());
        if (StringUtils.hasText(request.getType())) policy.setType(request.getType());
        if (StringUtils.hasText(request.getTaxMode())) policy.setTaxMode(request.getTaxMode());
        policy.setEffectiveDate(request.getEffectiveDate());
        policy.setExpiryDate(request.getExpiryDate());
        policyMapper.updateById(policy);
        return policy;
    }

    @Transactional(rollbackFor = Exception.class)
    public QuotationPolicy publishPolicy(Long id) {
        QuotationPolicy policy = ensurePolicyExists(id);
        if (!"DRAFT".equals(policy.getStatus())) throw new RuntimeException("仅草稿状态可发布");

        // 归档同type+taxMode的旧PUBLISHED策略
        LambdaQueryWrapper<QuotationPolicy> query = new LambdaQueryWrapper<>();
        query.eq(QuotationPolicy::getType, policy.getType())
                .eq(QuotationPolicy::getTaxMode, policy.getTaxMode())
                .eq(QuotationPolicy::getStatus, "PUBLISHED");
        List<QuotationPolicy> oldPublished = policyMapper.selectList(query);
        for (QuotationPolicy old : oldPublished) {
            old.setStatus("ARCHIVED");
            policyMapper.updateById(old);
        }

        policy.setStatus("PUBLISHED");
        policy.setVersion(policy.getVersion() + 1);
        policyMapper.updateById(policy);
        return policy;
    }

    @Transactional(rollbackFor = Exception.class)
    public void archivePolicy(Long id) {
        QuotationPolicy policy = ensurePolicyExists(id);
        if (!"PUBLISHED".equals(policy.getStatus())) throw new RuntimeException("仅已发布策略可归档");
        policy.setStatus("ARCHIVED");
        policyMapper.updateById(policy);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePolicy(Long id) {
        QuotationPolicy policy = ensurePolicyExists(id);
        if (!"DRAFT".equals(policy.getStatus())) throw new RuntimeException("仅草稿状态可删除");
        LambdaQueryWrapper<QuotationPolicyItem> itemQuery = new LambdaQueryWrapper<>();
        itemQuery.eq(QuotationPolicyItem::getPolicyId, id);
        itemMapper.delete(itemQuery);
        policyMapper.deleteById(id);
    }

    public List<QuotationPolicyItem> listPolicyItems(Long policyId) {
        LambdaQueryWrapper<QuotationPolicyItem> query = new LambdaQueryWrapper<>();
        query.eq(QuotationPolicyItem::getPolicyId, policyId)
                .orderByAsc(QuotationPolicyItem::getSortOrder);
        return itemMapper.selectList(query);
    }

    @Transactional(rollbackFor = Exception.class)
    public void addPolicyItems(Long policyId, List<QuotationPolicyItemRequest> requests) {
        QuotationPolicy policy = ensurePolicyExists(policyId);
        if (!"DRAFT".equals(policy.getStatus())) throw new RuntimeException("仅草稿状态可添加明细项");

        for (QuotationPolicyItemRequest req : requests) {
            QuotationPolicyItem item = new QuotationPolicyItem();
            item.setPolicyId(policyId);
            applyItem(item, req);
            item.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(item);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updatePolicyItem(Long itemId, QuotationPolicyItemRequest request) {
        QuotationPolicyItem item = itemMapper.selectById(itemId);
        if (item == null) throw new RuntimeException("明细项不存在");

        QuotationPolicy policy = ensurePolicyExists(item.getPolicyId());
        if (!"DRAFT".equals(policy.getStatus())) throw new RuntimeException("仅草稿状态可编辑明细项");

        applyItem(item, request);
        itemMapper.updateById(item);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePolicyItem(Long itemId) {
        QuotationPolicyItem item = itemMapper.selectById(itemId);
        if (item == null) throw new RuntimeException("明细项不存在");

        QuotationPolicy policy = ensurePolicyExists(item.getPolicyId());
        if (!"DRAFT".equals(policy.getStatus())) throw new RuntimeException("仅草稿状态可删除明细项");

        itemMapper.deleteById(itemId);
    }

    private QuotationPolicy ensurePolicyExists(Long id) {
        QuotationPolicy policy = policyMapper.selectById(id);
        if (policy == null) throw new RuntimeException("报价策略不存在");
        return policy;
    }

    private void applyItem(QuotationPolicyItem item, QuotationPolicyItemRequest req) {
        item.setSection(req.getSection());
        item.setCategory(req.getCategory());
        item.setItemKey(req.getItemKey());
        item.setItemName(req.getItemName());
        item.setDescription(req.getDescription());
        item.setPriceDescription(req.getPriceDescription());
        item.setIsRequired(req.getIsRequired() != null ? req.getIsRequired() : 0);
        item.setUnitPrice(req.getUnitPrice());
        item.setTaxRate(req.getTaxRate());
        item.setChargeMethod(req.getChargeMethod());
        item.setChargeUnit(req.getChargeUnit());
        item.setRemark(req.getRemark());
        item.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);
    }

    private QuotationPolicyVO toVO(QuotationPolicy policy, List<QuotationPolicyItem> items) {
        QuotationPolicyVO vo = new QuotationPolicyVO();
        vo.setId(policy.getId());
        vo.setName(policy.getName());
        vo.setType(policy.getType());
        vo.setTaxMode(policy.getTaxMode());
        vo.setVersion(policy.getVersion());
        vo.setStatus(policy.getStatus());
        vo.setEffectiveDate(policy.getEffectiveDate());
        vo.setExpiryDate(policy.getExpiryDate());
        vo.setCreatedBy(policy.getCreatedBy());
        vo.setCreatedAt(policy.getCreatedAt());
        vo.setUpdatedAt(policy.getUpdatedAt());
        vo.setItems(items);
        return vo;
    }
}