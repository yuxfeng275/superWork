package com.bu.management.sync.collector;

import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.service.WorktimeConfigService;
import com.bu.management.service.WorktimeContractSyncService;
import com.bu.management.sync.SyncOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 工时系统合同明细采集器：包装现有 {@link WorktimeContractSyncService}（upsert 与归属判定不变）。
 * scope = 年份（空则当年）。
 */
@Component
@RequiredArgsConstructor
public class WorktimeContractCollector implements DataCollector {

    public static final String TASK_CODE = "worktime-contract";

    private final WorktimeContractSyncService contractSyncService;
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
        int year = StringUtils.hasText(scope) ? Integer.parseInt(scope.trim()) : LocalDate.now().getYear();
        WorktimeSyncLog log = contractSyncService.syncContracts(year, triggeredBy);
        return List.of(toOutcome(log));
    }

    static SyncOutcome toOutcome(WorktimeSyncLog log) {
        boolean success = "success".equals(log.getStatus());
        return new SyncOutcome(
                "contract",
                log.getScope(),
                success,
                log.getTotalCount(),
                log.getUpsertCount(),
                log.getPendingCount(),
                log.getMessage());
    }
}
