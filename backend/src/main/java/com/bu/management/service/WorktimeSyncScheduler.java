package com.bu.management.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 工时系统数据同步定时任务。
 * 合同明细：每日同步（工时系统 02:00 从 OA 同步之后执行）；
 * 工时/成本：每日检查工时系统月度确认状态，已确认月份自动拉取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorktimeSyncScheduler {

    private final WorktimeConfigService configService;
    private final WorktimeContractSyncService contractSyncService;
    private final WorktimeMonthlySyncService monthlySyncService;

    @Scheduled(cron = "${worktime.contract-sync-cron:0 30 6 * * *}", zone = "Asia/Shanghai")
    public void dailyContractSync() {
        if (!configService.getRuntimeConfig().isConfigured()) {
            return;
        }
        try {
            contractSyncService.syncContracts(LocalDate.now().getYear(), "schedule");
        } catch (RuntimeException ex) {
            log.error("工时系统合同明细定时同步失败", ex);
        }
    }

    @Scheduled(cron = "${worktime.monthly-sync-cron:0 0 7 * * *}", zone = "Asia/Shanghai")
    public void monthlyWorklogAndCostSync() {
        if (!configService.getRuntimeConfig().isConfigured()) {
            return;
        }
        try {
            monthlySyncService.syncConfirmedMonths(null, "schedule");
        } catch (RuntimeException ex) {
            log.error("工时系统工时/成本定时同步失败", ex);
        }
    }
}
