package com.bu.management.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.annotation.RequirePermission;
import com.bu.management.config.WorktimeRuntimeConfig;
import com.bu.management.entity.DataSyncLog;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.integration.WorktimeApiClient;
import com.bu.management.mapper.WorktimeSyncLogMapper;
import com.bu.management.service.WorktimeConfigService;
import com.bu.management.sync.SyncOrchestrator;
import com.bu.management.sync.collector.WorktimeFullSyncCollector;
import com.bu.management.vo.Result;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "工时系统集成", description = "工时系统（worktime）连接配置与数据自动同步")
@RestController
@RequestMapping("/api/worktime")
@RequiredArgsConstructor
@RequirePermission({"kpi:manage"})
public class WorktimeIntegrationController {

    private final WorktimeConfigService configService;
    private final WorktimeApiClient apiClient;
    private final SyncOrchestrator syncOrchestrator;
    private final WorktimeSyncLogMapper syncLogMapper;

    // ==================== 配置管理 ====================

    @GetMapping("/status")
    @Operation(summary = "获取工时系统集成状态")
    public Result<Map<String, Object>> getStatus() {
        WorktimeRuntimeConfig config = configService.getRuntimeConfig();
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", config.enabled());
        status.put("baseUrl", config.baseUrl());
        status.put("credentialConfigured", config.hasCredentials());
        status.put("credentialSource", config.credentialSource());
        status.put("lastTestedAt", config.lastTestedAt());
        status.put("lastTestStatus", config.lastTestStatus());
        status.put("lastTestMessage", config.lastTestMessage());
        // 最近一次各类型同步结果
        for (String type : List.of("contract", "worklog", "cost")) {
            WorktimeSyncLog latest = syncLogMapper.selectOne(new LambdaQueryWrapper<WorktimeSyncLog>()
                    .eq(WorktimeSyncLog::getSyncType, type)
                    .orderByDesc(WorktimeSyncLog::getId)
                    .last("LIMIT 1"));
            status.put("last" + type.substring(0, 1).toUpperCase() + type.substring(1) + "Sync", latest);
        }
        return Result.success(status);
    }

    // ==================== 手动同步 ====================

    @PostMapping("/sync")
    @Operation(summary = "一键同步工时系统：合同明细 + 工时/成本 + 业务线利润月报（month=YYYY-MM 可强制某月）")
    public Result<List<DataSyncLog>> sync(@RequestParam(required = false) String month,
                                          @RequestAttribute("userId") Long userId) {
        return Result.success("工时系统同步完成",
                syncOrchestrator.run(WorktimeFullSyncCollector.TASK_CODE, month, "manual", userId));
    }

    // ==================== 同步日志 ====================

    @GetMapping("/sync/logs")
    @Operation(summary = "同步日志")
    public Result<List<WorktimeSyncLog>> syncLogs(@RequestParam(required = false) String syncType) {
        return Result.success(syncLogMapper.selectList(new LambdaQueryWrapper<WorktimeSyncLog>()
                .eq(syncType != null && !syncType.isBlank(), WorktimeSyncLog::getSyncType, syncType)
                .orderByDesc(WorktimeSyncLog::getId)
                .last("LIMIT 50")));
    }
}
