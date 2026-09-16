package com.bu.management.sync.collector;

import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.service.WorktimeConfigService;
import com.bu.management.service.WorktimeMonthlySyncService;
import com.bu.management.sync.SyncOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 工时系统工时/成本月度采集器：包装现有 {@link WorktimeMonthlySyncService}。
 * scope = 强制月份 YYYY-MM（空则按工时系统已确认月份自动扫描）。
 * 一个任务产出 worklog / cost 两个域的结果。
 */
@Component
@RequiredArgsConstructor
public class WorktimeMonthlyCollector implements DataCollector {

    public static final String TASK_CODE = "worktime-monthly";

    private final WorktimeMonthlySyncService monthlySyncService;
    private final WorktimeConfigService configService;

    @Override
    public String taskCode() {
        return TASK_CODE;
    }

    @Override
    public List<SyncOutcome> collect(String scope, String triggeredBy) {
        // 沿用原定时任务行为：未配置工时系统连接时静默跳过
        if (!configService.getRuntimeConfig().isConfigured()) {
            return List.of();
        }
        String forceMonth = StringUtils.hasText(scope) ? scope.trim() : null;
        List<WorktimeSyncLog> logs = monthlySyncService.syncConfirmedMonths(forceMonth, triggeredBy);
        List<SyncOutcome> outcomes = new ArrayList<>();
        for (WorktimeSyncLog log : logs) {
            outcomes.add(new SyncOutcome(
                    log.getSyncType(),
                    log.getScope(),
                    "success".equals(log.getStatus()),
                    log.getTotalCount(),
                    log.getUpsertCount(),
                    log.getPendingCount(),
                    log.getMessage()));
        }
        return outcomes;
    }
}
