package com.bu.management.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 项目事实层定时汇入：每 30 分钟把「最近 6 小时有变动」的会议/邮件/工作项/大事儿抄进 project_fact。
 *
 * <p>它只做汇入，不派活——派活由 agent-autoloop 的规则决定（见 docs/project-agent-loop-plan.md §4.1）。
 * 关闭开关：project.fact.ingest-enabled=false；周期：project.fact.ingest-cron。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectFactIngestScheduler {

    private final ProjectFactService factService;

    @Value("${project.fact.ingest-enabled:true}")
    private boolean enabled;

    @Value("${project.fact.ingest-hours:6}")
    private int lookbackHours;

    @Scheduled(cron = "${project.fact.ingest-cron:0 */30 * * * *}", zone = "Asia/Shanghai")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            int changed = factService.ingestAllSince(LocalDateTime.now().minusHours(lookbackHours));
            if (changed > 0) {
                log.info("项目事实层汇入 {} 条", changed);
            }
        } catch (Exception e) {
            log.error("项目事实层汇入失败", e);
        }
    }
}
