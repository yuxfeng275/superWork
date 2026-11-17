package com.bu.management.service;

import com.bu.management.entity.AiProactiveEvent;
import com.bu.management.mapper.AiProactiveEventMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 主动性的事件入口：业务侧「有事发生」时调这里，落一行事件并按需重扫建议。
 *
 * <p>先落事件再刷新建议，两个原因：
 * <ol>
 *   <li>事件是审计证据：能回答「这条建议是事件触发的还是打开页面顺手扫的」；</li>
 *   <li>刷新有 10 分钟节流：邮件同步可能每几分钟跑一次，不能每次都全量扫规则。</li>
 * </ol>
 *
 * <p>事件写入或刷新失败只记日志，绝不反向影响业务（同步/纪要/周报的成败与它无关）。
 *
 * @author BU Team
 * @since 2026-11-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProactiveEventService {

    /** 同一用户两次事件刷新之间的最小间隔：够快看到新建议，又不至于把规则扫描打成高频任务。 */
    static final Duration REFRESH_INTERVAL = Duration.ofMinutes(10);

    private final AiProactiveEventMapper eventMapper;

    private final AiProactiveService proactiveService;

    private final Map<Long, Instant> lastRefresh = new ConcurrentHashMap<>();

    /**
     * 记录一次事件并（节流后）刷新该用户的建议。
     *
     * @param userId 事件归属人；null 直接忽略（系统事件不产生个人建议）
     * @param kind   事件类型，取 {@link AiProactiveEvent} 里的常量
     * @param refId  来源对象 ID，可为空
     */
    public void record(Long userId, String kind, String refId) {
        if (userId == null || kind == null) {
            return;
        }
        try {
            AiProactiveEvent event = new AiProactiveEvent();
            event.setUserId(userId);
            event.setKind(kind);
            event.setRefId(refId);
            event.setCreatedAt(LocalDateTime.now());
            eventMapper.insert(event);
        } catch (Exception e) {
            log.warn("主动性事件落库失败（不影响业务）userId={} kind={}: {}", userId, kind, e.getMessage());
        }
        refreshIfDue(userId);
    }

    /** 事件触发重扫：同一用户 10 分钟内只扫一次。 */
    void refreshIfDue(Long userId) {
        Instant now = Instant.now();
        Instant last = lastRefresh.get(userId);
        if (last != null && Duration.between(last, now).compareTo(REFRESH_INTERVAL) < 0) {
            return;
        }
        lastRefresh.put(userId, now);
        try {
            proactiveService.refreshNow(userId);
        } catch (Exception e) {
            log.warn("事件触发建议扫描失败 userId={}: {}", userId, e.getMessage());
        }
    }

    /** 最近 N 天事件（新的在前），供 E2E 与排查用。 */
    public List<AiProactiveEvent> recent(Long userId, int days, int limit) {
        int window = days <= 0 ? 1 : Math.min(days, 30);
        int size = limit <= 0 ? 20 : Math.min(limit, 100);
        LocalDateTime since = LocalDate.now().minusDays(window).atStartOfDay();
        try {
            List<AiProactiveEvent> rows = eventMapper.recent(userId, since, size);
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("主动性事件查询失败 userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }
}
