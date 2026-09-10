package com.bu.management.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.annotation.RequirePermission;
import com.bu.management.config.WorktimeRuntimeConfig;
import com.bu.management.dto.WorktimeConfigRequest;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.integration.WorktimeApiClient;
import com.bu.management.mapper.WorktimeSyncLogMapper;
import com.bu.management.service.WorktimeConfigService;
import com.bu.management.service.WorktimeContractSyncService;
import com.bu.management.service.WorktimeMonthlySyncService;
import com.bu.management.vo.Result;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
    private final WorktimeContractSyncService contractSyncService;
    private final WorktimeMonthlySyncService monthlySyncService;
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

    @PutMapping("/config")
    @Operation(summary = "保存工时系统集成配置")
    public Result<WorktimeRuntimeConfig> saveConfig(
            @RequestBody WorktimeConfigRequest request,
            @RequestAttribute("userId") Long userId) {
        return Result.success("工时系统配置已保存", configService.save(request, userId));
    }

    @PostMapping("/connection-test")
    @Operation(summary = "测试工时系统连接（返回账号可见业务线范围）")
    public Result<Map<String, Object>> testConnection() {
        try {
            JsonNode filters = apiClient.testConnection();
            configService.recordConnectionTest(true, "连接成功", LocalDateTime.now());
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("visibleBusinessLines", filters.path("business_lines"));
            result.put("dataCutoffDate", filters.path("data_cutoff_date").asText(null));
            return Result.success(result);
        } catch (RuntimeException ex) {
            configService.recordConnectionTest(false, ex.getMessage(), LocalDateTime.now());
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", ex.getMessage());
            return Result.success(result);
        }
    }

    // ==================== 手动同步 ====================

    @PostMapping("/sync/contracts")
    @Operation(summary = "手动同步合同明细（默认当年）")
    public Result<WorktimeSyncLog> syncContracts(@RequestParam(required = false) Integer year) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        return Result.success("合同明细同步完成", contractSyncService.syncContracts(targetYear, "manual"));
    }

    @PostMapping("/sync/monthly")
    @Operation(summary = "手动同步工时/成本（forceMonth=YYYY-MM 时强制重拉该月）")
    public Result<List<WorktimeSyncLog>> syncMonthly(@RequestParam(required = false) String forceMonth) {
        return Result.success("月度数据同步完成", monthlySyncService.syncConfirmedMonths(forceMonth, "manual"));
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
