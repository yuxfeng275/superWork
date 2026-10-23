package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiProactivePush;
import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.integration.WeComClient;
import com.bu.management.mapper.AiProactivePushMapper;
import com.bu.management.mapper.EmailWeComMappingMapper;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 主动建议的站外到达：用户不登录系统时，每天一条企业微信消息（今日最值得做的 1~3 件事），
 * 消息里的链接直达 AI 助手并按建议预填提示词，点开就能继续对话。
 *
 * <p>约束：每人每天最多一条（(user_id, pushed_on) 唯一）；没有建议不发；未绑定企微不发（不硬塞邮件）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProactivePushService {

    /** 单条推送里最多列几件事：多了没人看。 */
    private static final int MAX_ITEMS = 3;
    private static final int MAX_ITEM_TITLE = 40;

    /** 推送结果：SENT 成功；SKIPPED 不该发（无建议/已发/未绑定）；FAILED 发送失败。 */
    public record PushResult(String status, String reason) {
        public boolean sent() {
            return AiProactivePush.STATUS_SENT.equals(status);
        }
    }

    private final AiProactivePushMapper pushMapper;
    private final EmailWeComMappingMapper mappingMapper;
    private final AiProactiveService proactiveService;
    private final WeComClient weComClient;

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    /** 立即给某个用户推一条今日简报（手动触发与定时任务共用）。 */
    public PushResult pushToday(Long userId) {
        LocalDate today = LocalDate.now();
        if (pushMapper.selectCount(new LambdaQueryWrapper<AiProactivePush>()
                .eq(AiProactivePush::getUserId, userId)
                .eq(AiProactivePush::getPushedOn, today)) > 0) {
            return new PushResult(AiProactivePush.STATUS_SKIPPED, "今天已推送过");
        }
        String wecomUserId = wecomUserIdOf(userId);
        if (!StringUtils.hasText(wecomUserId)) {
            return new PushResult(AiProactivePush.STATUS_SKIPPED, "未绑定企业微信，暂不推送");
        }
        List<AiProactiveService.Suggestion> suggestions = proactiveService.list(userId);
        if (suggestions.isEmpty()) {
            return new PushResult(AiProactivePush.STATUS_SKIPPED, "今天没有需要主动提醒的事");
        }
        List<AiProactiveService.Suggestion> picked = suggestions.stream().limit(MAX_ITEMS).toList();
        String content = render(today, picked);
        AiProactivePush row = new AiProactivePush();
        row.setUserId(userId);
        row.setChannel(AiProactivePush.CHANNEL_WECOM);
        row.setSuggestionIds(picked.stream().map(item -> String.valueOf(item.id()))
                .collect(Collectors.joining(",")));
        row.setContent(content);
        row.setPushedOn(today);
        row.setCreatedAt(LocalDateTime.now());
        try {
            weComClient.pushText(wecomUserId, content);
            row.setStatus(AiProactivePush.STATUS_SENT);
            save(row);
            return new PushResult(AiProactivePush.STATUS_SENT, null);
        } catch (Exception e) {
            row.setStatus(AiProactivePush.STATUS_FAILED);
            row.setReason(cap(e.getMessage(), 300));
            save(row);
            log.warn("AI 主动建议推送失败 userId={}: {}", userId, e.getMessage());
            return new PushResult(AiProactivePush.STATUS_FAILED, e.getMessage());
        }
    }

    /** 每天 08:50 的定时推送：只挑绑定企微的用户，逐人失败不互相影响。 */
    public void pushDaily() {
        List<Long> userIds = mappingMapper.selectList(new LambdaQueryWrapper<EmailWeComMapping>()
                        .eq(EmailWeComMapping::getEnabled, 1))
                .stream()
                .map(EmailWeComMapping::getOwnerUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        for (Long userId : userIds) {
            try {
                pushToday(userId);
            } catch (Exception e) {
                log.warn("AI 主动建议定时推送异常 userId={}: {}", userId, e.getMessage());
            }
        }
    }

    /** 推送文案：先说人话，再给三件事，最后一条直达链接。 */
    String render(LocalDate today, List<AiProactiveService.Suggestion> items) {
        StringBuilder sb = new StringBuilder();
        sb.append(greeting(LocalTime.now())).append("，今天最值得先做的是：\n");
        int index = 1;
        for (AiProactiveService.Suggestion item : items) {
            sb.append(index++).append(". ").append(cap(item.title(), MAX_ITEM_TITLE));
            if (StringUtils.hasText(item.body())) {
                sb.append("（").append(cap(item.body(), MAX_ITEM_TITLE)).append("）");
            }
            sb.append('\n');
        }
        sb.append("\n点开即可让我接着做，不用在系统里找：\n").append(deepLink(items.get(0)));
        return sb.toString();
    }

    /** 直达链接：/ai-assistant?prefill=<首条建议的提示词>，前端会填进输入框。 */
    private String deepLink(AiProactiveService.Suggestion item) {
        String base = StringUtils.hasText(publicBaseUrl) ? publicBaseUrl.replaceAll("/+$", "") : "";
        String prompt = StringUtils.hasText(item.prompt()) ? item.prompt() : item.title();
        return base + "/ai-assistant?prefill=" + encode(prompt);
    }

    private static String greeting(LocalTime now) {
        if (now.isBefore(LocalTime.of(11, 0))) {
            return "早上好";
        }
        if (now.isBefore(LocalTime.of(14, 0))) {
            return "中午好";
        }
        if (now.isBefore(LocalTime.of(19, 0))) {
            return "下午好";
        }
        return "晚上好";
    }

    private String wecomUserIdOf(Long userId) {
        EmailWeComMapping mapping = mappingMapper.selectOne(new LambdaQueryWrapper<EmailWeComMapping>()
                .eq(EmailWeComMapping::getOwnerUserId, userId)
                .last("LIMIT 1"));
        if (mapping == null || !Integer.valueOf(1).equals(mapping.getEnabled())) {
            return null;
        }
        return mapping.getWecomUserId();
    }

    private void save(AiProactivePush row) {
        try {
            pushMapper.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发定时任务/手动触发撞车：保留先写入的一条
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static String cap(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replaceAll("[\\r\\n]+", " ").trim();
        return trimmed.length() > max ? trimmed.substring(0, max) + "…" : trimmed;
    }
}
