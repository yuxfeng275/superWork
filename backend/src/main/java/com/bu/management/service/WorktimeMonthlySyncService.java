package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueMonthClose;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.integration.WorktimeApiClient;
import com.bu.management.mapper.RevenueMonthCloseMapper;
import com.bu.management.mapper.WorktimeSyncLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 工时系统工时明细 + 成本分析月度自动同步。
 * 触发条件：工时系统 available-months 中该月状态为「已确认」(confirmed)，
 * 且本系统该月未完结（revenue_month_close 无记录）。
 * 工时明细走 xlsx 现有导入管道（整月覆盖）；成本分析走 JSON 解析（整月覆盖）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorktimeMonthlySyncService {

    private final WorktimeApiClient apiClient;
    private final RevenueImportService revenueImportService;
    private final RevenueMappingResolver mappingResolver;
    private final WorktimeSyncLogMapper syncLogMapper;
    private final RevenueMonthCloseMapper monthCloseMapper;

    /**
     * 扫描工时系统已确认月份，对尚未自动同步过的月份执行工时+成本同步。
     * forceMonth 非空时强制同步该月（手动触发用）。
     */
    public List<WorktimeSyncLog> syncConfirmedMonths(String forceMonth, String triggeredBy) {
        List<WorktimeSyncLog> logs = new ArrayList<>();
        JsonNode months = apiClient.fetchAvailableMonths();
        if (!months.isArray()) {
            return logs;
        }
        // 账号可见业务线（工时系统 id 列表），导出工时明细用
        List<Long> wtBlIds = new ArrayList<>();
        apiClient.testConnection().path("business_lines")
                .forEach(bl -> wtBlIds.add(bl.path("id").asLong()));

        for (JsonNode monthNode : months) {
            String yearMonth = monthNode.path("year_month").asText(null);
            String status = monthNode.path("status").asText(null);
            if (!StringUtils.hasText(yearMonth)) {
                continue;
            }
            boolean force = yearMonth.equals(forceMonth);
            if (!force && !"confirmed".equals(status)) {
                continue;
            }
            if (isMonthClosed(yearMonth)) {
                continue;
            }
            if (!force && alreadySynced("worklog", yearMonth) && alreadySynced("cost", yearMonth)) {
                continue;
            }
            if (force || !alreadySynced("worklog", yearMonth)) {
                logs.add(syncWorklog(yearMonth, wtBlIds, triggeredBy));
            }
            if (force || !alreadySynced("cost", yearMonth)) {
                logs.add(syncCost(yearMonth, triggeredBy));
            }
        }
        return logs;
    }

    private WorktimeSyncLog syncWorklog(String yearMonth, List<Long> wtBlIds, String triggeredBy) {
        WorktimeSyncLog syncLog = newLog("worklog", yearMonth, triggeredBy);
        try {
            byte[] xlsx = apiClient.downloadWorklogBlDetail(wtBlIds, yearMonth);
            var result = revenueImportService.importWorklogStream(new ByteArrayInputStream(xlsx),
                    "worktime-bl-detail-" + yearMonth + ".xlsx", yearMonth, null);
            finishLog(syncLog, "success", result.getTotalCount(), result.getSuccessCount(),
                    result.getPendingCount(), "工时明细同步完成");
        } catch (RuntimeException ex) {
            log.error("工时系统工时明细同步失败 {}", yearMonth, ex);
            finishLog(syncLog, "failed", 0, 0, 0, ex.getMessage());
        }
        return syncLog;
    }

    private WorktimeSyncLog syncCost(String yearMonth, String triggeredBy) {
        WorktimeSyncLog syncLog = newLog("cost", yearMonth, triggeredBy);
        try {
            List<JsonNode> rows = apiClient.fetchAllCostByProject(yearMonth, yearMonth);
            List<RevenueCostEntry> entries = new ArrayList<>();
            for (JsonNode row : rows) {
                RevenueCostEntry entry = new RevenueCostEntry();
                entry.setYearMonth(row.path("year_month").asText(yearMonth));
                String businessLine = text(row, "business_line_name");
                String projectName = text(row, "project_name");
                if (!StringUtils.hasText(projectName)) {
                    continue;
                }
                entry.setBusinessLineName(businessLine);
                entry.setProjectNameRaw(projectName);
                entry.setEmployeeCount(intValue(row, "employee_count"));
                entry.setHours(decimal(row, "total_hours"));
                entry.setCostAmount(decimal(row, "total_labor_cost"));
                entry.setPersonMonthCost(decimal(row, "hourly_cost"));
                if (entry.getHours() == null || entry.getCostAmount() == null) {
                    continue;
                }
                RevenueMappingResolver.Resolved resolved = mappingResolver.resolve(businessLine, projectName, null);
                entry.setBusinessLineId(resolved.businessLineId());
                entry.setProjectId(resolved.projectId());
                entry.setWorkType(resolved.workType());
                entry.setSalesKind(resolved.salesKind());
                entry.setSalesProjectId(resolved.salesProjectId());
                entry.setPending(resolved.pending() ? 1 : 0);
                entries.add(entry);
            }
            var result = revenueImportService.saveCostEntries(entries,
                    "worktime-cost-" + yearMonth + "(auto)", null);
            finishLog(syncLog, "success", result.getTotalCount(), result.getSuccessCount(),
                    result.getPendingCount(), "成本分析同步完成");
        } catch (RuntimeException ex) {
            log.error("工时系统成本分析同步失败 {}", yearMonth, ex);
            finishLog(syncLog, "failed", 0, 0, 0, ex.getMessage());
        }
        return syncLog;
    }

    private boolean isMonthClosed(String yearMonth) {
        Long count = monthCloseMapper.selectCount(new LambdaQueryWrapper<RevenueMonthClose>()
                .eq(RevenueMonthClose::getYearMonth, yearMonth)
                .isNotNull(RevenueMonthClose::getClosedAt));
        return count != null && count > 0;
    }

    private boolean alreadySynced(String syncType, String yearMonth) {
        Long count = syncLogMapper.selectCount(new LambdaQueryWrapper<WorktimeSyncLog>()
                .eq(WorktimeSyncLog::getSyncType, syncType)
                .eq(WorktimeSyncLog::getScope, yearMonth)
                .eq(WorktimeSyncLog::getStatus, "success"));
        return count != null && count > 0;
    }

    private WorktimeSyncLog newLog(String syncType, String scope, String triggeredBy) {
        WorktimeSyncLog syncLog = new WorktimeSyncLog();
        syncLog.setSyncType(syncType);
        syncLog.setScope(scope);
        syncLog.setStatus("running");
        syncLog.setTriggeredBy(triggeredBy);
        syncLog.setStartedAt(LocalDateTime.now());
        syncLogMapper.insert(syncLog);
        return syncLog;
    }

    private void finishLog(WorktimeSyncLog syncLog, String status, int total, int upserted, int pending, String message) {
        syncLog.setStatus(status);
        syncLog.setTotalCount(total);
        syncLog.setUpsertCount(upserted);
        syncLog.setPendingCount(pending);
        syncLog.setMessage(message == null || message.length() <= 1000 ? message : message.substring(0, 1000));
        syncLog.setFinishedAt(LocalDateTime.now());
        syncLogMapper.updateById(syncLog);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }
}
