package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.QuotationDiscountDecisionRequest;
import com.bu.management.entity.Quotation;
import com.bu.management.entity.QuotationDiscountRequest;
import com.bu.management.entity.QuotationPolicy;
import com.bu.management.mapper.QuotationDiscountRequestMapper;
import com.bu.management.mapper.QuotationMapper;
import com.bu.management.mapper.QuotationPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 折扣审批：低于策略底价的报价必须留一份可追溯的申请与审批意见，审批通过前不允许进入 APPROVED。
 */
@Service
@RequiredArgsConstructor
public class QuotationDiscountService {

    private final QuotationDiscountRequestMapper requestMapper;
    private final QuotationMapper quotationMapper;
    private final QuotationPolicyMapper policyMapper;
    private final QuotationWorkflowService workflowService;

    @Transactional(rollbackFor = Exception.class)
    public QuotationDiscountRequest request(Long quotationId, QuotationDiscountDecisionRequest payload) {
        Quotation quotation = quotationMapper.selectById(quotationId);
        if (quotation == null) throw new RuntimeException("报价单不存在");
        if (!"DRAFT".equals(quotation.getStatus()) && !"INTERNAL_REVIEW".equals(quotation.getStatus())) {
            throw new RuntimeException("仅草稿或审核中的报价单可申请折扣");
        }

        QuotationPolicy policy = quotation.getPolicyId() != null
                ? policyMapper.selectById(quotation.getPolicyId()) : null;

        QuotationDiscountRequest entity = new QuotationDiscountRequest();
        entity.setQuotationId(quotationId);
        entity.setRequestedBy(payload.getRequestedBy());
        entity.setRequestedDiscount(payload.getRequestedDiscount());
        entity.setFloorDiscount(policy != null ? policy.getMinDiscountRate() : null);
        entity.setGrossMarginRate(quotation.getGrossMarginRate());
        entity.setReason(payload.getReason());
        entity.setStatus("PENDING");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        requestMapper.insert(entity);

        workflowService.log(quotationId, quotation.getStatus(), quotation.getStatus(), "DISCOUNT_REQUEST",
                payload.getRequestedBy(), payload.getReason());
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public QuotationDiscountRequest decide(Long requestId, QuotationDiscountDecisionRequest payload) {
        QuotationDiscountRequest entity = requestMapper.selectById(requestId);
        if (entity == null) throw new RuntimeException("折扣申请不存在");
        if (!"PENDING".equals(entity.getStatus())) throw new RuntimeException("该折扣申请已处理");

        String decision = payload.getStatus();
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new RuntimeException("审批结论只能是 APPROVED 或 REJECTED");
        }
        entity.setStatus(decision);
        entity.setApprover(payload.getApprover());
        entity.setApprovedAt(LocalDateTime.now());
        entity.setComment(payload.getComment());
        entity.setUpdatedAt(LocalDateTime.now());
        requestMapper.updateById(entity);

        workflowService.log(entity.getQuotationId(), null, null,
                "APPROVED".equals(decision) ? "DISCOUNT_APPROVE" : "DISCOUNT_REJECT",
                payload.getApprover(),
                StringUtils.hasText(payload.getComment()) ? payload.getComment() : entity.getReason());
        return entity;
    }

    public List<QuotationDiscountRequest> list(String status) {
        LambdaQueryWrapper<QuotationDiscountRequest> query = new LambdaQueryWrapper<>();
        query.eq(StringUtils.hasText(status), QuotationDiscountRequest::getStatus, status)
                .orderByDesc(QuotationDiscountRequest::getCreatedAt);
        return requestMapper.selectList(query);
    }
}
