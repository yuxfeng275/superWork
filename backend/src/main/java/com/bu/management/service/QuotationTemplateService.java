package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Quotation;
import com.bu.management.entity.QuotationTemplate;
import com.bu.management.entity.QuotationTemplateClause;
import com.bu.management.mapper.QuotationTemplateClauseMapper;
import com.bu.management.mapper.QuotationTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报价模板：把“报价说明/条款”从手填文本变成可复用的条款库。
 * 条款正文支持 {{customerName}} {{quotationNo}} {{quoteDate}} {{validityDays}} 等占位符。
 */
@Service
@RequiredArgsConstructor
public class QuotationTemplateService {

    private final QuotationTemplateMapper templateMapper;
    private final QuotationTemplateClauseMapper clauseMapper;

    public List<QuotationTemplate> list(String status) {
        LambdaQueryWrapper<QuotationTemplate> query = new LambdaQueryWrapper<>();
        query.eq(StringUtils.hasText(status), QuotationTemplate::getStatus, status)
                .orderByAsc(QuotationTemplate::getType)
                .orderByAsc(QuotationTemplate::getName);
        return templateMapper.selectList(query);
    }

    public QuotationTemplate get(Long id) {
        QuotationTemplate template = templateMapper.selectById(id);
        if (template == null) throw new RuntimeException("报价模板不存在");
        return template;
    }

    public List<QuotationTemplateClause> clauses(Long templateId) {
        LambdaQueryWrapper<QuotationTemplateClause> query = new LambdaQueryWrapper<>();
        query.eq(QuotationTemplateClause::getTemplateId, templateId)
                .orderByAsc(QuotationTemplateClause::getSortOrder);
        return clauseMapper.selectList(query);
    }

    @Transactional(rollbackFor = Exception.class)
    public QuotationTemplate save(QuotationTemplate template) {
        if (!StringUtils.hasText(template.getName())) throw new RuntimeException("模板名称不能为空");
        LocalDateTime now = LocalDateTime.now();
        template.setUpdatedAt(now);
        if (template.getId() == null) {
            if (!StringUtils.hasText(template.getStatus())) template.setStatus("DRAFT");
            if (template.getDefaultValidityDays() == null) template.setDefaultValidityDays(30);
            if (!StringUtils.hasText(template.getInvoiceType())) template.setInvoiceType("增值税专用发票");
            template.setCreatedAt(now);
            templateMapper.insert(template);
        } else {
            templateMapper.updateById(template);
        }
        return template;
    }

    @Transactional(rollbackFor = Exception.class)
    public void replaceClauses(Long templateId, List<QuotationTemplateClause> clauses) {
        get(templateId);
        clauseMapper.delete(new LambdaQueryWrapper<QuotationTemplateClause>()
                .eq(QuotationTemplateClause::getTemplateId, templateId));
        if (clauses == null) return;
        int sort = 0;
        for (QuotationTemplateClause clause : clauses) {
            clause.setId(null);
            clause.setTemplateId(templateId);
            if (clause.getIsEnabled() == null) clause.setIsEnabled(1);
            clause.setSortOrder(sort++);
            clause.setCreatedAt(LocalDateTime.now());
            clause.setUpdatedAt(LocalDateTime.now());
            clauseMapper.insert(clause);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void publish(Long templateId) {
        QuotationTemplate template = get(templateId);
        template.setStatus("PUBLISHED");
        template.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(template);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long templateId) {
        get(templateId);
        clauseMapper.delete(new LambdaQueryWrapper<QuotationTemplateClause>()
                .eq(QuotationTemplateClause::getTemplateId, templateId));
        templateMapper.deleteById(templateId);
    }

    /** 渲染模板条款：按分组归并后返回，供前端预览与导出使用。 */
    public Map<String, List<String>> render(Long templateId, Quotation quotation) {
        if (templateId == null) return Map.of();
        Map<String, List<String>> rendered = new LinkedHashMap<>();
        for (QuotationTemplateClause clause : clauses(templateId)) {
            if (clause.getIsEnabled() != null && clause.getIsEnabled() == 0) continue;
            String text = interpolate(clause.getContent(), quotation);
            if (!StringUtils.hasText(text)) continue;
            String section = StringUtils.hasText(clause.getSection()) ? clause.getSection() : "其他条款";
            rendered.computeIfAbsent(section, k -> new ArrayList<>()).add(text);
        }
        return rendered;
    }

    public String buildNote(Long templateId, Quotation quotation) {
        StringBuilder sb = new StringBuilder();
        render(templateId, quotation).forEach((section, lines) -> {
            sb.append("【").append(section).append("】");
            lines.forEach(line -> sb.append("\n").append(line));
            sb.append("\n");
        });
        return sb.toString().trim();
    }

    private String interpolate(String content, Quotation quotation) {
        if (!StringUtils.hasText(content)) return null;
        if (quotation == null) return content;
        String text = content
                .replace("{{customerName}}", nvl(quotation.getCustomerName()))
                .replace("{{quotationNo}}", nvl(quotation.getQuotationNo()))
                .replace("{{quoteDate}}", quotation.getQuoteDate() != null
                        ? quotation.getQuoteDate().format(DateTimeFormatter.ISO_DATE) : "")
                .replace("{{validityDays}}", quotation.getValidityDays() != null
                        ? String.valueOf(quotation.getValidityDays()) : "30")
                .replace("{{deliveryPeriod}}", nvl(quotation.getDeliveryPeriod()))
                .replace("{{invoiceType}}", nvl(quotation.getInvoiceType()))
                .replace("{{currency}}", nvl(quotation.getCurrency()));
        return text;
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }
}
