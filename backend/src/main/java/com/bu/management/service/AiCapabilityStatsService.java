package com.bu.management.service;

import com.bu.management.mapper.AiCapabilityCallMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 能力调用可观测：回答「哪个工具在跑、成功率多少、超时与门禁拒绝各多少」。
 *
 * <p>口径：
 * <ul>
 *   <li>成功率 = okCalls / calls；超时率 = TIMEOUT / calls；门禁拒绝率 = GATE_DENIED / calls；</li>
 *   <li>calls 为 0 时所有比率返回 0，不出现 NaN；</li>
 *   <li>只统计窗口内 {@code created_at} 的行：审计表允许丢行（写库失败不影响调用），
 *       所以这里的数字是下限，不是账本。</li>
 * </ul>
 *
 * @author BU Team
 * @since 2026-11-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCapabilityStatsService {

    /** 默认窗口：一周够看出趋势，又不至于把慢能力的噪声抹平。 */
    public static final int DEFAULT_DAYS = 7;

    /** 窗口上限：90 天，防止一条请求扫全表。 */
    public static final int MAX_DAYS = 90;

    private final AiCapabilityCallMapper callMapper;

    /**
     * 单个能力的统计。
     *
     * @param capabilityId   能力编码
     * @param calls          调用次数
     * @param okCalls        成功次数
     * @param failedCalls    失败次数
     * @param timeouts       超时次数
     * @param gateDenied     门禁拒绝次数
     * @param avgLatencyMs   平均耗时
     * @param successRate    成功率
     * @param timeoutRate    超时率
     * @param gateDeniedRate 门禁拒绝率
     * @param lastCalledAt   最近调用时间
     */
    public record CapabilityStat(String capabilityId,
                                 long calls,
                                 long okCalls,
                                 long failedCalls,
                                 long timeouts,
                                 long gateDenied,
                                 long avgLatencyMs,
                                 double successRate,
                                 double timeoutRate,
                                 double gateDeniedRate,
                                 LocalDateTime lastCalledAt) {
    }

    /**
     * 窗口报告。
     *
     * @param days         统计天数（已夹到 1..90）
     * @param since        窗口起点
     * @param totalCalls   窗口内总调用次数
     * @param capabilities 按调用次数倒序的能力列表
     */
    public record Report(int days, LocalDateTime since, long totalCalls,
                         List<CapabilityStat> capabilities) {
    }

    /** 出报告；聚合失败返回空报告并记日志，看板打不开比数字缺失更糟。 */
    public Report report(Integer days) {
        int window = days == null || days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        LocalDateTime since = LocalDateTime.now().minusDays(window);
        List<Map<String, Object>> rows;
        try {
            rows = callMapper.statsSince(since);
        } catch (Exception e) {
            log.warn("能力调用统计失败，返回空报告: err={}", e.getMessage());
            return new Report(window, since, 0, List.of());
        }
        List<CapabilityStat> stats = new ArrayList<>();
        long total = 0;
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            long calls = number(row.get("calls"));
            long okCalls = number(row.get("okCalls"));
            long failedCalls = number(row.get("failedCalls"));
            long timeouts = number(row.get("timeouts"));
            long gateDenied = number(row.get("gateDenied"));
            stats.add(new CapabilityStat(
                    text(row.get("capabilityId")),
                    calls, okCalls, failedCalls, timeouts, gateDenied,
                    number(row.get("avgLatencyMs")),
                    rate(okCalls, calls),
                    rate(timeouts, calls),
                    rate(gateDenied, calls),
                    time(row.get("lastCalledAt"))));
            total += calls;
        }
        return new Report(window, since, total, stats);
    }

    /** 比率的唯一算法：分母 0 返回 0，保留四位小数。 */
    private static double rate(long numerator, long denominator) {
        return denominator <= 0 ? 0d : Math.round(numerator * 10000d / denominator) / 10000d;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return new BigDecimal(String.valueOf(value)).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
