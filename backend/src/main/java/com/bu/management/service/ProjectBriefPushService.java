package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiBrief;
import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.integration.BarkClient;
import com.bu.management.integration.WeComClient;
import com.bu.management.mapper.AiBriefMapper;
import com.bu.management.mapper.EmailWeComMappingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 晨报站外到达：每天把「今天该做的事」推到手机上（一人一条，不群发）。
 *
 * <p>通道选择：该用户绑了企微 <b>且</b> 企微已配置 → 走企微；否则 Bark 已配置 → 走 Bark；
 * 两个通道都不可用不发（返回 false，只 warn 一次，不标 delivered）。
 * 没配 app.public-base-url 不推死链；已送达的不重复发（brief.delivered_at）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectBriefPushService {

    private final AiBriefMapper briefMapper;
    private final EmailWeComMappingMapper mappingMapper;
    private final ProjectBriefService briefService;
    private final WeComClient weComClient;
    private final BarkClient barkClient;
    private final EmailIntegrationConfigService emailIntegrationConfigService;
    private final ObjectMapper objectMapper;

    /** 推送正文总长上限（手机上一屏能读完）。 */
    private static final int MAX_BODY = 900;
    /** 清单最多排 3 条。 */
    private static final int MAX_ITEMS = 3;
    /** why 一行的上限。 */
    private static final int MAX_WHY = 120;
    /** 解析不了 JSON 时的纯文本上限（老行为）。 */
    private static final int MAX_PLAIN = 800;

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
        // 通道 1：绑定存在 **且** 企微已配置 才走企微。
        // 只判绑定的话，企微未配置时 pushText 抛 IllegalStateException，异常被 pushDate 吞掉，
        // Bark 就永远不会被尝试。
        String wecomUserId = wecomUserIdOf(brief.getUserId());
        if (StringUtils.hasText(wecomUserId) && weComConfigured()) {
            weComClient.pushText(wecomUserId, render(brief));
            briefService.markDelivered(brief.getId());
            return true;
        }
        // 通道 2：Bark（iOS 推送）；title 带上晨报日期，正文复用 render，详情链走 /workbench
        if (barkClient.isConfigured()) {
            String detailUrl = StringUtils.hasText(publicBaseUrl) ? publicBaseUrl + "/workbench" : null;
            if (!barkClient.push(titleOf(brief), render(brief), detailUrl)) {
                return false;
            }
            briefService.markDelivered(brief.getId());
            return true;
        }
        // 两个通道都不可用：不标记已送达，下次还会重试
        log.warn("晨报推送跳过（企微未配置或未绑定，且 Bark 未配置）: briefId={}, userId={}",
                brief.getId(), brief.getUserId());
        return false;
    }

    /** 企微通道是否可用：口径与 WeComClient 内部一致（EmailIntegrationConfigService 运行时配置）。 */
    private boolean weComConfigured() {
        try {
            return emailIntegrationConfigService.getRuntimeConfig().isWeComConfigured();
        } catch (Exception e) {
            log.debug("读取企微配置失败，按未配置处理: {}", e.getMessage());
            return false;
        }
    }

    /** 两个通道共用的标题：企微正文首行与 Bark 的 title 是同一句。 */
    String titleOf(AiBrief brief) {
        if (brief.getBriefDate() == null) {
            return "【今日安排】";
        }
        return "【今日安排】 · " + brief.getBriefDate();
    }

    /**
     * 推送正文：content 是 {"items":[{title,why,links}]} 时排成手机上能读的清单；
     * 解析不了或不是该结构就退回纯文本前 800 字（老行为）。总长压到 900 字以内。
     */
    String render(AiBrief brief) {
        String content = brief.getContent() == null ? "" : brief.getContent();
        StringBuilder sb = new StringBuilder();
        if (!appendItems(brief, content, sb)) {
            sb.append("【今日安排】").append('\n');
            sb.append(content.length() > MAX_PLAIN ? content.substring(0, MAX_PLAIN) + "…" : content);
        }
        if (StringUtils.hasText(publicBaseUrl)) {
            String detail = "\n详情：" + publicBaseUrl + "/workbench";
            if (sb.length() + detail.length() <= MAX_BODY) {
                sb.append(detail);
            }
        }
        return sb.length() > MAX_BODY ? sb.substring(0, MAX_BODY - 1) + "…" : sb.toString();
    }

    /** JSON 结构成立且至少排出一条时返回 true；否则不动 sb，交给纯文本兜底。 */
    private boolean appendItems(AiBrief brief, String content, StringBuilder sb) {
        JsonNode items = itemsOf(content);
        if (items == null) {
            return false;
        }
        StringBuilder body = new StringBuilder();
        int index = 0;
        for (JsonNode item : items) {
            if (index >= MAX_ITEMS) {
                break;
            }
            String title = textOf(item, "title");
            if (!StringUtils.hasText(title)) {
                continue;
            }
            index++;
            body.append('\n').append(index).append(". ").append(title.strip());
            String why = textOf(item, "why");
            if (StringUtils.hasText(why)) {
                body.append("\n   ").append(clip(why.strip(), MAX_WHY));
            }
            String link = firstHttpLink(item.path("links"));
            if (link != null) {
                body.append("\n   ").append(link);
            }
        }
        if (index == 0) {
            return false;
        }
        sb.append(titleOf(brief)).append(body);
        return true;
    }

    private JsonNode itemsOf(String content) {
        String text = content.strip();
        if (!text.startsWith("{")) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            JsonNode items = root == null ? null : root.get("items");
            return items != null && items.isArray() && !items.isEmpty() ? items : null;
        } catch (Exception e) {
            log.debug("晨报内容不是 JSON，退回纯文本: {}", e.getMessage());
            return null;
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String firstHttpLink(JsonNode links) {
        if (links == null || !links.isArray()) {
            return null;
        }
        for (JsonNode link : links) {
            String url = link.asText("");
            if (url.startsWith("http")) {
                return url.strip();
            }
        }
        return null;
    }

    private static String clip(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    /**
     * 该用户可用企微推送的 wecomUserId；未绑定或绑定的推送开关没打开都算“没开企微推送”。
     *
     * <p>口径与 {@code EmailRiskAlertService} 一致：映射为空 **或** enabled != 1 就当作没有企微通道，
     * 由调用方自然降级到 Bark。
     */
    private String wecomUserIdOf(Long userId) {
        EmailWeComMapping mapping = mappingMapper.selectOne(new LambdaQueryWrapper<EmailWeComMapping>()
                .eq(EmailWeComMapping::getOwnerUserId, userId)
                .last("limit 1"));
        if (mapping == null || !Integer.valueOf(1).equals(mapping.getEnabled())) {
            return null;
        }
        return mapping.getWecomUserId();
    }
}
