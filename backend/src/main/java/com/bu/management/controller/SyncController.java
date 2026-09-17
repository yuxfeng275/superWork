package com.bu.management.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.annotation.RequirePermission;
import com.bu.management.entity.DataSyncLog;
import com.bu.management.entity.SyncTask;
import com.bu.management.sync.SyncOrchestrator;
import com.bu.management.sync.SyncTaskService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "数据同步中心", description = "各数据域（合同/工时/成本/组织/人员）同步任务统一编排：定时配置、手动触发、统一日志")
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
@RequirePermission({"sync:manage"})
public class SyncController {

    private final SyncTaskService taskService;
    private final SyncOrchestrator orchestrator;
    private final com.bu.management.mapper.DataSyncLogMapper syncLogMapper;

    @GetMapping("/tasks")
    @Operation(summary = "同步任务列表（含最近状态）")
    public Result<List<SyncTask>> listTasks() {
        return Result.success(taskService.listTasks());
    }

    @PutMapping("/tasks/{taskCode}")
    @Operation(summary = "更新任务 cron / 启用状态")
    public Result<SyncTask> updateTask(@PathVariable String taskCode,
                                       @RequestBody SyncTaskUpdateRequest request) {
        return Result.success("同步任务已更新",
                taskService.updateTask(taskCode, request.getCron(), request.getEnabled()));
    }

    @PostMapping("/tasks/{taskCode}/run")
    @Operation(summary = "手动触发同步（scope 语义随任务：合同=年份，月度=YYYY-MM）")
    public Result<List<DataSyncLog>> runTask(@PathVariable String taskCode,
                                             @RequestBody(required = false) SyncRunRequest request,
                                             @RequestAttribute("userId") Long userId) {
        String scope = request == null ? null : request.getScope();
        return Result.success("同步已执行",
                orchestrator.run(taskCode, scope, "manual", userId));
    }

    @GetMapping("/logs")
    @Operation(summary = "统一同步日志（按域/状态过滤，最近 100 条）")
    public Result<List<DataSyncLog>> listLogs(@RequestParam(required = false) String domain,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String taskCode) {
        return Result.success(syncLogMapper.selectList(new LambdaQueryWrapper<DataSyncLog>()
                .eq(domain != null && !domain.isBlank(), DataSyncLog::getDomain, domain)
                .eq(status != null && !status.isBlank(), DataSyncLog::getStatus, status)
                .eq(taskCode != null && !taskCode.isBlank(), DataSyncLog::getTaskCode, taskCode)
                .orderByDesc(DataSyncLog::getId)
                .last("LIMIT 100")));
    }

    @GetMapping("/overview")
    @Operation(summary = "各数据域最近一次成功同步摘要（数据截至展示）")
    public Result<List<Map<String, Object>>> overview() {
        return Result.success(taskService.overview());
    }

    @Data
    public static class SyncTaskUpdateRequest {
        private String cron;
        private Boolean enabled;
    }

    @Data
    public static class SyncRunRequest {
        private String scope;
    }
}
