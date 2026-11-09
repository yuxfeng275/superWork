package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.SalesOpportunityFollowUpRequest;
import com.bu.management.dto.SalesOpportunityRequest;
import com.bu.management.dto.SalesOpportunitySupportWorkLogRequest;
import com.bu.management.entity.Quotation;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.entity.SalesOpportunitySupportWorkLog;
import com.bu.management.entity.User;
import com.bu.management.mapper.SalesOpportunityFollowUpMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.bu.management.mapper.SalesOpportunitySupportWorkLogMapper;
import com.bu.management.mapper.QuotationMapper;
import com.bu.management.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SalesOpportunityService {
    private final SalesOpportunityMapper mapper;
    private final SalesOpportunitySupportWorkLogMapper supportWorkLogMapper;
    private final SalesOpportunityFollowUpMapper followUpMapper;
    private final QuotationMapper quotationMapper;
    private final UserMapper userMapper;

    private static final List<String> STAGES_REQUIRING_APPROVAL = List.of("商务谈判", "已成交");

    /** 默认下次跟进：本次跟进日期的下一周同一天（商机按周跟进）。 */
    private static final int DEFAULT_NEXT_FOLLOW_UP_DAYS = 7;
    /** 默认下次跟进的时刻，固定 10:00。 */
    private static final LocalTime DEFAULT_NEXT_FOLLOW_UP_TIME = LocalTime.of(10, 0);
    /** 中文星期，索引 = {@code DayOfWeek.getValue() - 1}。 */
    private static final String[] WEEKDAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

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
        LocalDateTime followUpAt = request.getFollowUpAt() != null ? request.getFollowUpAt() : now;
        // 下次跟进：手填优先；缺省按「本次跟进日期的下一周同一天 10:00」推算，文案写成 MM-dd 周X
        applyNextFollowUp(opportunity, request.getNextFollowUp(), followUpAt.toLocalDate(), true);

        SalesOpportunityFollowUp item = new SalesOpportunityFollowUp();
        item.setOpportunityId(opportunityId);
        item.setFollowUpAt(followUpAt);
        item.setFollower(request.getFollower().trim());
        item.setContent(request.getContent().trim());
        item.setStatus(request.getStatus());
        item.setProbability(request.getProbability());
        item.setNextFollowUp(opportunity.getNextFollowUp());
        item.setCreatedAt(now);
        followUpMapper.insert(item);

        opportunity.setStatus(request.getStatus());
        opportunity.setProbability(request.getProbability());
        // 人工填写的新跟进 = 下一轮从「待跟进」开始
        opportunity.setFollowUpState("PENDING");
        applyOwnerUserId(opportunity, opportunity.getOwner());
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
        apply(item, request, true);
        item.setCreatedAt(LocalDateTime.now());
        mapper.insert(item);
        return item;
    }

    public SalesOpportunity update(Long id, SalesOpportunityRequest request) {
        SalesOpportunity item = mapper.selectById(id);
        if (item == null) throw new RuntimeException("商机不存在");
        apply(item, request, false);
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

    /**
     * 新建/编辑共用的字段拷贝。
     *
     * @param creating {@code true}=新建（没填下次跟进也要给默认值）；{@code false}=编辑（没填就保持库里已有值，避免误清空）
     */
    private void apply(SalesOpportunity item, SalesOpportunityRequest request, boolean creating) {
        item.setName(request.getName()); item.setCustomer(request.getCustomer()); item.setType(request.getType()); item.setStatus(request.getStatus());
        item.setAmount(request.getAmount()); item.setOwner(request.getOwner()); item.setBusinessLine(request.getBusinessLine());
        item.setProbability(request.getProbability()); item.setExpectedClose(request.getExpectedClose()); item.setSource(request.getSource()); item.setNote(request.getNote());
        applyNextFollowUp(item, request.getNextFollowUp(), LocalDate.now(), creating);
        applyOwnerUserId(item, request.getOwner());
    }

    /**
     * 「下次跟进」的唯一写入口径：手填且能解析 → 用用户的值；否则按周推算默认值并格式化文案。
     *
     * <p>业务口径：商机<b>按周跟进</b>，下次跟进默认 = 本次跟进的下一周同一天，时间固定 10:00，
     * 文案统一写成 {@code MM-dd 周X}（中文星期）。新建商机、编辑商机、新增跟进三个入口都调这里，
     * 只保留一套逻辑，避免「页面填了、台账没填」这类两套口径。
     *
     * @param item        目标商机
     * @param rawText     调用方填写的原文，可为空
     * @param baseDate    基准日期（新增跟进取本次跟进日期；新建/编辑取今天）
     * @param fillDefault 为空或解析不出时是否补默认值；编辑入口传 {@code false}，不填就不动已有值
     */
    private void applyNextFollowUp(SalesOpportunity item, String rawText, LocalDate baseDate,
                                   boolean fillDefault) {
        LocalDate base = baseDate == null ? LocalDate.now() : baseDate;
        if (StringUtils.hasText(rawText)) {
            LocalDateTime parsed = parseNextFollowUpAt(rawText, base);
            if (parsed != null) {
                // 用户填了且解析得出：完全用用户的值，不覆盖
                item.setNextFollowUp(rawText.trim());
                item.setNextFollowUpAt(parsed);
                return;
            }
        }
        if (!fillDefault) {
            return;
        }
        LocalDateTime next = defaultNextFollowUpAt(base);
        item.setNextFollowUp(formatNextFollowUp(next));
        item.setNextFollowUpAt(next);
    }

    /** 默认下次跟进 = 基准日期 + 7 天，时间固定 10:00。 */
    private LocalDateTime defaultNextFollowUpAt(LocalDate baseDate) {
        return baseDate.plusDays(DEFAULT_NEXT_FOLLOW_UP_DAYS).atTime(DEFAULT_NEXT_FOLLOW_UP_TIME);
    }

    /** 默认文案口径：{@code MM-dd 周X}。 */
    private String formatNextFollowUp(LocalDateTime nextFollowUpAt) {
        return String.format(Locale.ROOT, "%02d-%02d %s",
                nextFollowUpAt.getMonthValue(), nextFollowUpAt.getDayOfMonth(),
                WEEKDAY_NAMES[nextFollowUpAt.getDayOfWeek().getValue() - 1]);
    }

    /**
     * 把「下次跟进」自由文本解析成结构化时间；解析不出来返回 {@code null}，由调用方决定是否补默认值。
     *
     * <p>解析统一走 {@link OpportunityFollowUpParser}，与 {@code OpportunityFollowUpService#backfillNextFollowUpAt()}
     * 保持同一套口径，避免两条写入路径对同一句话给出不同结果。
     */
    private LocalDateTime parseNextFollowUpAt(String text, LocalDate baseDate) {
        return OpportunityFollowUpParser.parseNextFollowUp(text, baseDate);
    }

    /**
     * 按 {@code owner} 文本回填 {@code owner_user_id}：与 {@code user.real_name} <b>完全相等且唯一</b>才写。
     *
     * <p>匹配不上就什么都不写（新建即 NULL，编辑保留库里已有值）：<b>不猜、不回退到试点人</b>。
     * 试点期 owner 姓名大量对不上账号，硬写一个 owner_user_id 会让问句路由发错人。
     */
    private void applyOwnerUserId(SalesOpportunity item, String owner) {
        Long userId = matchOwnerUserId(owner);
        if (userId != null) {
            item.setOwnerUserId(userId);
        }
    }

    /** 姓名 → 用户 ID；没有命中或命中多条都返回 {@code null}（宁可空着也不猜）。 */
    private Long matchOwnerUserId(String owner) {
        if (!StringUtils.hasText(owner)) {
            return null;
        }
        try {
            List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                    .eq(User::getRealName, owner.strip()));
            if (users == null || users.size() != 1) {
                return null;
            }
            User user = users.get(0);
            return user == null ? null : user.getId();
        } catch (Exception e) {
            // 映射失败不能阻断商机写库：owner_user_id 留空，问句走站内兜底
            return null;
        }
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
