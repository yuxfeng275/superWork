package com.bu.management.service;

import com.bu.management.mapper.KbDocumentMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 知识库语料健康度：把「有多少、多少能用、多少该复核、有多少重复」一次算清楚。
 *
 * <p>口径：
 * <ul>
 *   <li>{@code enabled=0} 是人工下线的文档：不进检索，但留档，可随时再开；</li>
 *   <li>{@code stale} 是超过 180 天没更新的文档：<b>只提示，不自动下线</b>；</li>
 *   <li>{@code duplicates} 是内容校验和相同的文档组数：常见于同一篇文档从语雀与手工入口各入一次，
 *       检索时同文多条会挤掉别的结果，需要人工挑一条留用。</li>
 * </ul>
 *
 * <p>聚合放 SQL（GROUP BY），不在 Java 里全表捞：知识库条数会随语雀导入增长，
 * 管理页每次打开都全量加载会把页面拖慢。
 *
 * @author BU Team
 * @since 2026-11-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbHealthService {

    /** 过期未更新阈值（天）：与事实保留期同口径，只用于提示复核。 */
    public static final int STALE_DAYS = 180;

    private final KbDocumentMapper documentMapper;

    /**
     * 健康度报告。
     *
     * @param total        文档总数
     * @param enabled      参与检索的文档数
     * @param disabled     已下线（留档）的文档数
     * @param stale        超过 {@value #STALE_DAYS} 天未更新的文档数
     * @param duplicates   内容重复的文档组数
     * @param lastUpdatedAt 全库最近更新时间，空库为 null
     * @param staleDays    过期口径，随响应返回，前端不用硬编码
     * @param sources      按来源拆分
     */
    public record Report(long total,
                         long enabled,
                         long disabled,
                         long stale,
                         long duplicates,
                         LocalDateTime lastUpdatedAt,
                         int staleDays,
                         List<SourceHealth> sources) {
    }

    /**
     * 单个来源的健康度。
     *
     * @param sourceType  MANUAL / YUQUE / UPLOAD / CASE
     * @param total       该来源文档数
     * @param enabled     启用数
     * @param disabled    停用数
     * @param stale       过期未更新数
     * @param lastUpdatedAt 该来源最近更新时间
     */
    public record SourceHealth(String sourceType,
                               long total,
                               long enabled,
                               long disabled,
                               long stale,
                               LocalDateTime lastUpdatedAt) {
    }

    /** 汇总健康度；聚合失败时返回空报告并记日志，不让管理页整页打不开。 */
    public Report report() {
        List<Map<String, Object>> rows;
        int duplicates;
        try {
            rows = documentMapper.statsBySource();
            duplicates = documentMapper.countDuplicateChecksums();
        } catch (Exception e) {
            log.warn("知识库健康度聚合失败，返回空报告: err={}", e.getMessage());
            return new Report(0, 0, 0, 0, 0, null, STALE_DAYS, List.of());
        }
        List<SourceHealth> sources = new ArrayList<>();
        long total = 0;
        long enabled = 0;
        long disabled = 0;
        long stale = 0;
        LocalDateTime lastUpdatedAt = null;
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            SourceHealth source = new SourceHealth(
                    text(row.get("sourceType")),
                    number(row.get("total")),
                    number(row.get("enabled")),
                    number(row.get("disabled")),
                    number(row.get("stale")),
                    time(row.get("lastUpdatedAt")));
            sources.add(source);
            total += source.total();
            enabled += source.enabled();
            disabled += source.disabled();
            stale += source.stale();
            if (source.lastUpdatedAt() != null
                    && (lastUpdatedAt == null || source.lastUpdatedAt().isAfter(lastUpdatedAt))) {
                lastUpdatedAt = source.lastUpdatedAt();
            }
        }
        return new Report(total, enabled, disabled, stale, duplicates, lastUpdatedAt,
                STALE_DAYS, sources);
    }

    /** 列名大小写在不同驱动/别名下会变，统一按字符串取值。 */
    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** COUNT/SUM 可能回 Long / BigInteger / BigDecimal，统一收成 long。 */
    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** MySQL DATETIME 可能映射成 Timestamp 或 LocalDateTime，两种都接住。 */
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
