package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.SalesOpportunityFollowUpRequest;
import com.bu.management.dto.SalesOpportunityRequest;
import com.bu.management.dto.SalesOpportunitySupportWorkLogRequest;
import com.bu.management.entity.Quotation;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.entity.SalesOpportunitySupportWorkLog;
import com.bu.management.mapper.SalesOpportunityFollowUpMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.bu.management.mapper.SalesOpportunitySupportWorkLogMapper;
import com.bu.management.mapper.QuotationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SalesOpportunityService {
    private final SalesOpportunityMapper mapper;
    private final SalesOpportunitySupportWorkLogMapper supportWorkLogMapper;
    private final SalesOpportunityFollowUpMapper followUpMapper;
    private final QuotationMapper quotationMapper;

    private static final List<String> STAGES_REQUIRING_APPROVAL = List.of("商务谈判", "已成交");

    public List<SalesOpportunity> list(String keyword, String type, String status, String owner, String businessLine) {
        LambdaQueryWrapper<SalesOpportunity> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) query.and(w -> w.like(SalesOpportunity::getName, keyword).or().like(SalesOpportunity::getCustomer, keyword).or().like(SalesOpportunity::getOwner, keyword));
        query.eq(StringUtils.hasText(type), SalesOpportunity::getType, type)
                .eq(StringUtils.hasText(status), SalesOpportunity::getStatus, status)
                .eq(StringUtils.hasText(owner), SalesOpportunity::getOwner, owner)
                .eq(StringUtils.hasText(businessLine), SalesOpportunity::getBusinessLine, businessLine)
                .orderByDesc(SalesOpportunity::getCreatedAt);
        return mapper.selectList(query);
    }

    public SalesOpportunity get(Long id) { return mapper.selectById(id); }

    /** 商机下已存在的报价单数量，用于阶段守卫与联动展示。 */
    public long quotationCount(Long opportunityId) {
        return quotationMapper.selectCount(new LambdaQueryWrapper<Quotation>()
                .eq(Quotation::getOpportunityId, opportunityId));
    }

    public List<SalesOpportunityFollowUp> listFollowUps(Long opportunityId) {
        ensureExists(opportunityId);
        LambdaQueryWrapper<SalesOpportunityFollowUp> query = new LambdaQueryWrapper<>();
        query.eq(SalesOpportunityFollowUp::getOpportunityId, opportunityId)
                .orderByDesc(SalesOpportunityFollowUp::getFollowUpAt)
                .orderByDesc(SalesOpportunityFollowUp::getCreatedAt);
        return followUpMapper.selectList(query);
    }

    @Transactional(rollbackFor = Exception.class)
    public SalesOpportunityFollowUp createFollowUp(Long opportunityId, SalesOpportunityFollowUpRequest request) {
        SalesOpportunity opportunity = ensureExists(opportunityId);
        if (!StringUtils.hasText(request.getFollower())) throw new RuntimeException("跟进人不能为空");
        if (!StringUtils.hasText(request.getContent())) throw new RuntimeException("跟进内容不能为空");
        if (!StringUtils.hasText(request.getStatus())) throw new RuntimeException("商机阶段不能为空");
        if (request.getProbability() == null || request.getProbability() < 0 || request.getProbability() > 100) {
            throw new RuntimeException("成交概率必须在0到100之间");
        }
        ensureStageAllowed(opportunity, request.getStatus());

        LocalDateTime now = LocalDateTime.now();
        SalesOpportunityFollowUp item = new SalesOpportunityFollowUp();
        item.setOpportunityId(opportunityId);
        item.setFollowUpAt(request.getFollowUpAt() != null ? request.getFollowUpAt() : now);
        item.setFollower(request.getFollower().trim());
        item.setContent(request.getContent().trim());
        item.setStatus(request.getStatus());
        item.setProbability(request.getProbability());
        item.setNextFollowUp(request.getNextFollowUp());
        item.setCreatedAt(now);
        followUpMapper.insert(item);

        opportunity.setStatus(request.getStatus());
        opportunity.setProbability(request.getProbability());
        opportunity.setNextFollowUp(request.getNextFollowUp());
        mapper.updateById(opportunity);
        return item;
    }

    public List<SalesOpportunitySupportWorkLog> listSupportWorkLogs(Long opportunityId) {
        ensureExists(opportunityId);
        LambdaQueryWrapper<SalesOpportunitySupportWorkLog> query = new LambdaQueryWrapper<>();
        query.eq(SalesOpportunitySupportWorkLog::getOpportunityId, opportunityId)
                .orderByDesc(SalesOpportunitySupportWorkLog::getSupportDate)
                .orderByDesc(SalesOpportunitySupportWorkLog::getCreatedAt);
        return supportWorkLogMapper.selectList(query);
    }

    public SalesOpportunitySupportWorkLog createSupportWorkLog(Long opportunityId, SalesOpportunitySupportWorkLogRequest request) {
        SalesOpportunity opportunity = ensureExists(opportunityId);
        if ("已成交".equals(opportunity.getStatus())) {
            throw new RuntimeException("已成交商机不可再维护售前工时");
        }
        if (!StringUtils.hasText(request.getSupporter())) throw new RuntimeException("支持人员不能为空");
        if (request.getHours() == null || request.getHours().compareTo(BigDecimal.ZERO) <= 0) throw new RuntimeException("工时必须大于0");
        if (!StringUtils.hasText(request.getContent())) throw new RuntimeException("支持内容不能为空");

        SalesOpportunitySupportWorkLog item = new SalesOpportunitySupportWorkLog();
        item.setOpportunityId(opportunityId);
        item.setSupportDate(request.getSupportDate() != null ? request.getSupportDate() : LocalDate.now());
        item.setSupporter(request.getSupporter());
        item.setHours(request.getHours());
        item.setSupportType(StringUtils.hasText(request.getSupportType()) ? request.getSupportType() : "方案支持");
        item.setContent(request.getContent());
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        supportWorkLogMapper.insert(item);
        return item;
    }

    public SalesOpportunity create(SalesOpportunityRequest request) {
        SalesOpportunity item = new SalesOpportunity();
        apply(item, request);
        item.setCreatedAt(LocalDateTime.now());
        mapper.insert(item);
        return item;
    }

    public SalesOpportunity update(Long id, SalesOpportunityRequest request) {
        SalesOpportunity item = mapper.selectById(id);
        if (item == null) throw new RuntimeException("商机不存在");
        apply(item, request);
        mapper.updateById(item);
        return item;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (mapper.deleteById(id) == 0) throw new RuntimeException("商机不存在");
        LambdaQueryWrapper<SalesOpportunitySupportWorkLog> supportQuery = new LambdaQueryWrapper<>();
        supportQuery.eq(SalesOpportunitySupportWorkLog::getOpportunityId, id);
        supportWorkLogMapper.delete(supportQuery);
        LambdaQueryWrapper<SalesOpportunityFollowUp> followUpQuery = new LambdaQueryWrapper<>();
        followUpQuery.eq(SalesOpportunityFollowUp::getOpportunityId, id);
        followUpMapper.delete(followUpQuery);
    }

    private void apply(SalesOpportunity item, SalesOpportunityRequest request) {
        item.setName(request.getName()); item.setCustomer(request.getCustomer()); item.setType(request.getType()); item.setStatus(request.getStatus());
        item.setAmount(request.getAmount()); item.setOwner(request.getOwner()); item.setBusinessLine(request.getBusinessLine()); item.setNextFollowUp(request.getNextFollowUp());
        item.setProbability(request.getProbability()); item.setExpectedClose(request.getExpectedClose()); item.setSource(request.getSource()); item.setNote(request.getNote());
    }

    /**
     * 阶段守卫：报价已经发出后不能把商机拉回“初步接触/需求确认”；
     * 进入“方案报价”及以后必须已经有一张已批准的报价单。
     */
    private void ensureStageAllowed(SalesOpportunity opportunity, String targetStatus) {
        List<Quotation> quotations = quotationMapper.selectList(new LambdaQueryWrapper<Quotation>()
                .eq(Quotation::getOpportunityId, opportunity.getId()));
        boolean hasSent = quotations.stream().anyMatch(q -> List.of("SENT", "ACCEPTED").contains(q.getStatus()));
        boolean hasApproved = quotations.stream().anyMatch(q ->
                List.of("APPROVED", "SENT", "ACCEPTED").contains(q.getStatus()));

        if (hasSent && List.of("初步接触", "需求确认").contains(targetStatus)) {
            throw new RuntimeException("报价已发送给客户，商机阶段不能回退到「" + targetStatus + "」");
        }
        // 只要还在报价前的阶段（需求确认及以前）就不拦，让业务能先口头报个价；
        // 一旦要宣告进入商务谈判/成交，必须有一张已批准的报价单作为依据。
        if (STAGES_REQUIRING_APPROVAL.contains(targetStatus) && !hasApproved) {
            throw new RuntimeException("进入「" + targetStatus + "」前需要先完成一张报价单的审批");
        }
    }

    private SalesOpportunity ensureExists(Long id) {
        SalesOpportunity opportunity = mapper.selectById(id);
        if (opportunity == null) throw new RuntimeException("商机不存在");
        return opportunity;
    }
}
