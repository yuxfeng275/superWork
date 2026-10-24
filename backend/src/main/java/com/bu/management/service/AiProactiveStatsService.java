package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiProactivePush;
import com.bu.management.entity.AiProactiveSuggestion;
import com.bu.management.mapper.AiProactivePushMapper;
import com.bu.management.mapper.AiProactiveSuggestionMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 主动建议效果指标：采纳率（该不该提）、打扰率（是不是噪声）、触达与推送成功率。
 * 这三个数决定「加规则还是砍规则」，比感觉可靠。
 */
@Service
@RequiredArgsConstructor
public class AiProactiveStatsService {

    /** 单类建议的效果；autoResolved 是系统自行收回的条数（不计入采纳/打扰分母）。 */
    public record KindStat(String kind, long total, long done, long dismissed, long autoResolved,
                           long open, int adoptRate, int noiseRate) {}

    /** 用户视角汇总；天数窗口默认 30 天。 */
    public record Stats(int days, long total, long done, long dismissed, long autoResolved, long open,
                        int adoptRate, int noiseRate, long pushes, long pushesSent,
                        List<KindStat> byKind) {}

    private final AiProactiveSuggestionMapper suggestionMapper;
    private final AiProactivePushMapper pushMapper;

    public Stats stats(Long userId, int days) {
        int window = Math.max(1, Math.min(days, 90));
        LocalDateTime since = LocalDate.now().minusDays(window).atStartOfDay();
        List<AiProactiveSuggestion> rows = suggestionMapper.selectList(
                new LambdaQueryWrapper<AiProactiveSuggestion>()
                        .eq(AiProactiveSuggestion::getUserId, userId)
                        .ge(AiProactiveSuggestion::getCreatedAt, since));
        if (rows == null) {
            rows = List.of();
        }
        long done = 0;
        long dismissed = 0;
        long autoResolved = 0;
        long open = 0;
        Map<String, long[]> perKind = new LinkedHashMap<>();
        for (AiProactiveSuggestion row : rows) {
            // [0]=total [1]=done [2]=user dismissed [3]=open [4]=auto dismissed
            long[] counters = perKind.computeIfAbsent(row.getKind(), key -> new long[5]);
            counters[0]++;
            switch (String.valueOf(row.getStatus())) {
                case AiProactiveSuggestion.STATUS_DONE -> {
                    done++;
                    counters[1]++;
                }
                case AiProactiveSuggestion.STATUS_DISMISSED -> {
                    dismissed++;
                    counters[2]++;
                }
                case AiProactiveSuggestion.STATUS_AUTO_DISMISSED -> {
                    autoResolved++;
                    counters[4]++;
                }
                default -> {
                    open++;
                    counters[3]++;
                }
            }
        }
        Long pushes = pushMapper.selectCount(new LambdaQueryWrapper<AiProactivePush>()
                .eq(AiProactivePush::getUserId, userId)
                .ge(AiProactivePush::getCreatedAt, since));
        Long pushesSent = pushMapper.selectCount(new LambdaQueryWrapper<AiProactivePush>()
                .eq(AiProactivePush::getUserId, userId)
                .eq(AiProactivePush::getStatus, AiProactivePush.STATUS_SENT)
                .ge(AiProactivePush::getCreatedAt, since));
        List<KindStat> byKind = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : perKind.entrySet()) {
            long[] counters = entry.getValue();
            long visible = counters[0] - counters[4];
            byKind.add(new KindStat(entry.getKey(), counters[0], counters[1], counters[2],
                    counters[4], counters[3],
                    rate(counters[1], counters[1] + counters[2]),
                    rate(counters[2], visible)));
        }
        byKind.sort((a, b) -> Long.compare(b.total(), a.total()));
        long visibleRows = rows.size() - autoResolved;
        return new Stats(window, rows.size(), done, dismissed, autoResolved, open,
                rate(done, done + dismissed), rate(dismissed, visibleRows),
                pushes == null ? 0 : pushes, pushesSent == null ? 0 : pushesSent, byKind);
    }

    /** 百分比取整；分母为 0 时返回 0，避免页面上出现 NaN。 */
    private static int rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return (int) Math.round(numerator * 100.0 / denominator);
    }
}
