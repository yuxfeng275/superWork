package com.bu.management.service;

import com.bu.management.config.ProjectContextProperties;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 事实保留期清理：到期只清「原文正文」，事实行本身留着。
 *
 * <p>口径见 {@code docs/fact-intake.md}：
 * <ul>
 *   <li>只动 {@code FILE_UPLOAD}（本机文件）与 {@code CHAT_EXPORT}（聊天记录）这两类
 *       ——它们最容易带着原始隐私内容；</li>
 *   <li>只把 {@code body} 置 null（正文清空），{@code title} / {@code digest} /
 *       {@code source_ref} 与事实行全部保留，列表、摘要、溯源照旧可用；</li>
 *   <li>期限取 {@code project-context.fact-retention-days}，默认 180 天；
 *       执行时间取 {@code project-context.fact-retention-cron}，默认每天 03:30（Asia/Shanghai）。</li>
 * </ul>
 *
 * <p>日志只记条数，不记正文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactRetentionScheduler {

    /** 保留期缺省值（天）：配置缺省或非正数时回落到它。 */
    private static final int DEFAULT_RETENTION_DAYS = 180;

    /** 参与保留期清理的来源类型：只有手工投递的原文类。 */
    private static final List<String> RETENTION_SOURCE_TYPES = List.of(
            ProjectFactService.SOURCE_FILE_UPLOAD, ProjectFactService.SOURCE_CHAT_EXPORT);

    private final ProjectFactService factService;
    private final ProjectContextProperties properties;

    /** 按保留期清掉过期事实的 body（title / digest / source_ref 与事实行保留）。 */
    @Scheduled(cron = "${project-context.fact-retention-cron:0 30 3 * * *}", zone = "Asia/Shanghai")
    public void purgeExpired() {
        int days = properties.getFactRetentionDays() > 0
                ? properties.getFactRetentionDays()
                : DEFAULT_RETENTION_DAYS;
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        int purged = factService.purgeBodies(before, RETENTION_SOURCE_TYPES);
        log.info("事实保留期清理完成: 保留 {} 天，清理正文 {} 条（FILE_UPLOAD / CHAT_EXPORT，事实行保留）",
                days, purged);
    }
}
