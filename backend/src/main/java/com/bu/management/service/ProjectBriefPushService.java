package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiBrief;
import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.integration.WeComClient;
import com.bu.management.mapper.AiBriefMapper;
import com.bu.management.mapper.EmailWeComMappingMapper;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 晨报站外到达：每天把「今天该做的事」按人推到企业微信（一人一条，不群发）。
 *
 * <p>口径与主动建议推送一致：未绑定企微不发；没配 app.public-base-url 不推死链；
 * 已送达的不重复发（brief.delivered_at）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectBriefPushService {

    private final AiBriefMapper briefMapper;
    private final EmailWeComMappingMapper mappingMapper;
    private final ProjectBriefService briefService;
    private final WeComClient weComClient;

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    /** 推送某天的晨报；返回成功条数。 */
    public int pushDate(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now() : date;
        List<AiBrief> briefs = briefMapper.selectList(new LambdaQueryWrapper<AiBrief>()
                .eq(AiBrief::getBriefDate, target)
                .isNull(AiBrief::getDeliveredAt));
        int sent = 0;
        for (AiBrief brief : briefs) {
            try {
                if (pushOne(brief)) {
                    sent++;
                }
            } catch (Exception e) {
                log.warn("晨报推送失败: briefId={}, error={}", brief.getId(), e.getMessage());
            }
        }
        return sent;
    }

    private boolean pushOne(AiBrief brief) {
        if (brief.getUserId() == null) {
            return false;
        }
        String wecomUserId = wecomUserIdOf(brief.getUserId());
        if (!StringUtils.hasText(wecomUserId)) {
            return false;
        }
        weComClient.pushText(wecomUserId, render(brief));
        briefService.markDelivered(brief.getId());
        return true;
    }

    String render(AiBrief brief) {
        StringBuilder sb = new StringBuilder("【今日安排】");
        String body = brief.getContent() == null ? "" : brief.getContent();
        sb.append('\n').append(body.length() > 800 ? body.substring(0, 800) + "…" : body);
        if (StringUtils.hasText(publicBaseUrl)) {
            sb.append("\n详情：").append(publicBaseUrl).append("/workbench");
        }
        return sb.toString();
    }

    private String wecomUserIdOf(Long userId) {
        EmailWeComMapping mapping = mappingMapper.selectOne(new LambdaQueryWrapper<EmailWeComMapping>()
                .eq(EmailWeComMapping::getOwnerUserId, userId)
                .last("limit 1"));
        return mapping == null ? null : mapping.getWecomUserId();
    }
}
