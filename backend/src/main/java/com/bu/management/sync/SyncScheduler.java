package com.bu.management.sync;

import com.bu.management.entity.SyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 统一同步调度器：每分钟扫描 sync_task 表，按库内 cron 判定到期任务并执行。
 * 替代原 WorktimeSyncScheduler（注解硬编码 cron），支持页面动态改 cron/启停。
 * 宕机期间错过的执行只补跑一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SyncTaskService taskService;
    private final SyncOrchestrator orchestrator;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        for (SyncTask task : taskService.listTasks()) {
            if (task.getEnabled() == null || task.getEnabled() != 1
                    || !StringUtils.hasText(task.getCron())) {
                continue;
            }
            CronExpression cron;
            try {
                cron = CronExpression.parse(task.getCron());
            } catch (IllegalArgumentException ex) {
                log.warn("同步任务 {} cron 非法，跳过: {}", task.getTaskCode(), task.getCron());
                continue;
            }
            LocalDateTime base = task.getLastRunAt() != null ? task.getLastRunAt() : now.minusMinutes(5);
            LocalDateTime next = cron.next(base);
            if (next == null || next.isAfter(now)) {
                continue;
            }
            try {
                orchestrator.run(task.getTaskCode(), null, "schedule", null);
            } catch (RuntimeException ex) {
                log.error("同步任务 {} 定时执行失败", task.getTaskCode(), ex);
            }
        }
    }
}
