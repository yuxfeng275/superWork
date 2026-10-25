package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Quotation;
import com.bu.management.entity.QuotationApprovalLog;
import com.bu.management.entity.QuotationDiscountRequest;
import com.bu.management.mapper.QuotationApprovalLogMapper;
import com.bu.management.mapper.QuotationDiscountRequestMapper;
import com.bu.management.mapper.QuotationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报价单状态机 + 审计。
 *
 * <p>旧实现的 updateStatus 只校验“下一格”是否相邻，没有审批、没有意见、没有留痕。
 * 这里把流转规则集中成一张表，并在每次流转时写入 quotation_approval_log；
 * 进入 APPROVED 前会检查是否存在低于策略底价的折扣，若有则必须先有 APPROVED 的折扣审批单。
 */
@Service
@RequiredArgsConstructor
public class QuotationWorkflowService {

    private static final Map<String, List<String>> TRANSITIONS = Map.of(
            "DRAFT", List.of("INTERNAL_REVIEW"),
            "INTERNAL_REVIEW", List.of("APPROVED", "DRAFT"),
            "APPROVED", List.of("SENT", "WITHDRAWN"),
            "SENT", List.of("ACCEPTED", "REJECTED", "WITHDRAWN"),
            "ACCEPTED", List.of(),
            "REJECTED", List.of("DRAFT"),
            "WITHDRAWN", List.of("DRAFT"),
            "EXPIRED", List.of("DRAFT")
    );

    private final QuotationMapper quotationMapper;
    private final QuotationApprovalLogMapper approvalLogMapper;
    private final QuotationDiscountRequestMapper discountRequestMapper;
    private final SalesOpportunityLinkService opportunityLink;

    public List<String> availableTransitions(Quotation quotation) {
        if (quotation == null || quotation.getStatus() == null) return List.of();
        return TRANSITIONS.getOrDefault(quotation.getStatus(), List.of());
    }

    public void transition(Long quotationId, String targetStatus, String operator, String comment) {
        Quotation quotation = quotationMapper.selectById(quotationId);
        if (quotation == null) throw new RuntimeException("报价单不存在");

        String from = quotation.getStatus();
        if (!availableTransitions(quotation).contains(targetStatus)) {
            throw new RuntimeException("无效的状态流转: " + from + " -> " + targetStatus);
        }
        if ("APPROVED".equals(targetStatus)) ensureDiscountApproved(quotation);

        quotation.setStatus(targetStatus);
        quotation.setUpdatedAt(LocalDateTime.now());
        if ("DRAFT".equals(targetStatus)) {
            quotation.setIsPreferred(0);
            quotation.setPreferredAt(null);
        }
        quotationMapper.updateById(quotation);

        log(quotationId, from, targetStatus, actionOf(from, targetStatus), operator, comment);
        opportunityLink.onQuotationStatusChanged(quotation);
    }

    public void log(Long quotationId, String from, String to, String action, String operator, String comment) {
        QuotationApprovalLog log = new QuotationApprovalLog();
        log.setQuotationId(quotationId);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setAction(action);
        log.setOperator(operator);
        log.setComment(comment);
        log.setCreatedAt(LocalDateTime.now());
        approvalLogMapper.insert(log);
    }

    public List<QuotationApprovalLog> history(Long quotationId) {
        LambdaQueryWrapper<QuotationApprovalLog> query = new LambdaQueryWrapper<>();
        query.eq(QuotationApprovalLog::getQuotationId, quotationId)
                .orderByDesc(QuotationApprovalLog::getCreatedAt);
        return approvalLogMapper.selectList(query);
    }

    /** 低于底价的折扣必须走完审批才能进入 APPROVED。 */
    void ensureDiscountApproved(Quotation quotation) {
        if (quotation.getDiscountOverride() == null || quotation.getDiscountOverride() == 0) return;
        LambdaQueryWrapper<QuotationDiscountRequest> query = new LambdaQueryWrapper<>();
        query.eq(QuotationDiscountRequest::getQuotationId, quotation.getId())
                .eq(QuotationDiscountRequest::getStatus, "APPROVED")
                .orderByDesc(QuotationDiscountRequest::getApprovedAt)
                .last("LIMIT 1");
        if (discountRequestMapper.selectOne(query) == null) {
            throw new RuntimeException("报价存在低于策略底价的折扣，需先完成折扣审批");
        }
    }

    /** 过期任务：已批准/已发送且超过有效期的报价单自动置为 EXPIRED。 */
    public List<Long> expireOverdue() {
        LambdaQueryWrapper<Quotation> query = new LambdaQueryWrapper<>();
        query.in(Quotation::getStatus, List.of("APPROVED", "SENT"));
        List<Long> expired = new ArrayList<>();
        for (Quotation quotation : quotationMapper.selectList(query)) {
            if (quotation.getQuoteDate() == null || quotation.getValidityDays() == null) continue;
            LocalDate expireAt = quotation.getQuoteDate().plusDays(quotation.getValidityDays());
            if (expireAt.isBefore(LocalDate.now())) {
                quotation.setStatus("EXPIRED");
                quotation.setUpdatedAt(LocalDateTime.now());
                quotationMapper.updateById(quotation);
                log(quotation.getId(), "SENT", "EXPIRED", "EXPIRE", "system", "超过报价有效期自动失效");
                opportunityLink.onQuotationStatusChanged(quotation);
                expired.add(quotation.getId());
            }
        }
        return expired;
    }

    private static String actionOf(String from, String to) {
        if ("INTERNAL_REVIEW".equals(to)) return "SUBMIT";
        if ("APPROVED".equals(to)) return "APPROVE";
        if ("DRAFT".equals(to)) return "RETURN";
        if ("SENT".equals(to)) return "SEND";
        if ("ACCEPTED".equals(to)) return "ACCEPT";
        if ("REJECTED".equals(to)) return "REJECT";
        if ("WITHDRAWN".equals(to)) return "WITHDRAW";
        return StringUtils.hasText(to) ? to : "UNKNOWN";
    }
}
