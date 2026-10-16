package com.bu.management.sync.collector;

import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.service.WorktimeConfigService;
import com.bu.management.service.WorktimeFullSyncService;
import com.bu.management.sync.SyncOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工时系统统一同步采集器（T+1 定时 / 手动按钮共用）：
 * 一次拉齐 合同明细 + 工时/成本 + 业务线利润月报。
 * scope = 强制月份 YYYY-MM（空则按各子步骤的默认范围）。
 */
@Component
@RequiredArgsConstructor
public class WorktimeFullSyncCollector implements DataCollector {

    public static final String TASK_CODE = "worktime-sync";

    private final WorktimeFullSyncService fullSyncService;
    private final WorktimeConfigService configService;

    @Override
    public String taskCode() {
        return TASK_CODE;
    }

    @Override
    public List<SyncOutcome> collect(String scope, String triggeredBy) {
        // 未配置工时系统连接时静默跳过
        if (!configService.getRuntimeConfig().isConfigured()) {
            return List.of();
        }
        List<WorktimeSyncLog> logs = fullSyncService.sync(scope, triggeredBy);
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
