package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueContractImportBatch;
import com.bu.management.entity.WorktimeBusinessLineMapping;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.integration.WorktimeApiClient;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueContractImportBatchMapper;
import com.bu.management.mapper.WorktimeBusinessLineMappingMapper;
import com.bu.management.mapper.WorktimeSyncLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工时系统合同明细自动同步。
 * 工时系统每日 02:00 从 OA 同步合同明细，本服务每日按其账号可见业务线范围分页拉取
 * 销售报表 drilldown 明细，按 detail_record_id upsert 进 revenue_contract_entry，
 * 归属判定复用 {@link RevenueContractAssignment}；人工调整过归属的行不被覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorktimeContractSyncService {

    private static final int UPSERT_CHUNK = 500;

    private final WorktimeApiClient apiClient;
    private final RevenueContractEntryMapper contractEntryMapper;
    private final RevenueContractImportBatchMapper batchMapper;
    private final WorktimeSyncLogMapper syncLogMapper;
    private final WorktimeBusinessLineMappingMapper blMappingMapper;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;

    /** 同步指定年度合同明细。返回同步日志。 */
    public WorktimeSyncLog syncContracts(int year, String triggeredBy) {
        WorktimeSyncLog syncLog = newLog("contract", String.valueOf(year), triggeredBy);
        try {
            // 账号可见业务线范围（工时系统侧按账号数据权限过滤，天然限定 119-122）
            JsonNode filters = apiClient.testConnection();
            List<JsonNode> wtLines = new ArrayList<>();
            filters.path("business_lines").forEach(wtLines::add);
            if (wtLines.isEmpty()) {
                throw new IllegalStateException("工时系统账号无可见业务线，请检查账号数据权限");
            }

            AssignmentContext ctx = loadAssignmentContext();

            List<RevenueContractEntry> entries = new ArrayList<>();
            for (JsonNode wtLine : wtLines) {
                String wtName = wtLine.path("name").asText();
                Long wtLineId = wtLine.path("id").isNumber() ? wtLine.path("id").asLong() : null;
                List<JsonNode> rows = apiClient.fetchAllContractDetails(year, wtName);
                for (JsonNode row : rows) {
                    RevenueContractEntry entry = mapRow(row, wtLineId, ctx);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }

            // 批次历史（与手工导入共用一张表，file_name 标识来源）
            RevenueContractImportBatch batch = new RevenueContractImportBatch();
            batch.setFileName("worktime-sync-" + year + ( "schedule".equals(triggeredBy) ? "（定时）" : "（手动）"));
            batch.setTotalCount(entries.size());
            batch.setCreatedBy(null);
            int pendingCount = (int) entries.stream().filter(e -> Integer.valueOf(1).equals(e.getPending())).count();
            batch.setSuccessCount(entries.size() - pendingCount);
            batch.setPendingCount(pendingCount);
            batchMapper.insert(batch);
            entries.forEach(e -> e.setBatchId(batch.getId()));

            for (int i = 0; i < entries.size(); i += UPSERT_CHUNK) {
                contractEntryMapper.upsertBatch(entries.subList(i, Math.min(i + UPSERT_CHUNK, entries.size())));
            }

            finishLog(syncLog, "success", entries.size(), entries.size(), pendingCount,
                    "同步完成，业务线 " + wtLines.size() + " 条");
            return syncLog;
        } catch (RuntimeException ex) {
            log.error("工时系统合同明细同步失败", ex);
            finishLog(syncLog, "failed", 0, 0, 0, ex.getMessage());
            throw ex;
        }
    }

    private RevenueContractEntry mapRow(JsonNode row, Long wtLineId, AssignmentContext ctx) {
        String detailNo = text(row, "detail_record_id");
        if (!StringUtils.hasText(detailNo)) {
            return null;
        }
        RevenueContractEntry entry = new RevenueContractEntry();
        entry.setDetailNo(detailNo.trim());
        entry.setContractNo(text(row, "contract_id"));
        entry.setContractName(text(row, "contract_name"));
        entry.setBrand(text(row, "brand"));
        entry.setCustomer(text(row, "customer_name"));
        entry.setItemDesc(text(row, "payment_item_content"));
        String typeRaw = StringUtils.hasText(text(row, "business_line_original"))
                ? text(row, "business_line_original") : text(row, "business_line");
        entry.setBizLineRaw(typeRaw);
        entry.setReceivableAmount(decimal(row, "receivable_amount"));
        String saleMonth = monthText(text(row, "payment_sales_month"));
        if (saleMonth == null) {
            saleMonth = monthText(text(row, "receivable_date"));
        }
        entry.setSaleMonth(saleMonth);
        entry.setDeliveryDate(dateText(text(row, "project_delivery_date")));
        entry.setSmsCost(decimal(row, "sms_cost"));
        entry.setDirectCost(decimal(row, "direct_cost"));
        entry.setThirdPartyCost(decimal(row, "third_party_procurement_cost"));
        entry.setReceivedAmount(decimal(row, "received_amount"));
        entry.setPaymentStatus(text(row, "payment_status"));

        // 业务线归属：优先工时系统映射表，其次关键字匹配（与手工导入一致）
        Long localLineId = wtLineId == null ? null : ctx.wtToLocalLine().get(wtLineId);
        RevenueContractAssignment.Assigned assigned = localLineId != null
                ? RevenueContractAssignment.assignToLine(entry.getBrand(), localLineId,
                        ctx.lineMode(), ctx.projects(), ctx.enabledLineIds())
                : RevenueContractAssignment.assign(entry.getBrand(), typeRaw,
                        ctx.lines(), ctx.lineMode(), ctx.projects(), ctx.enabledLineIds());
        entry.setBizLineId(assigned.lineId());
        entry.setProjectId(assigned.projectId());
        entry.setPending(assigned.pending() ? 1 : 0);
        return entry;
    }

    private AssignmentContext loadAssignmentContext() {
        List<BusinessLine> lines = businessLineMapper.selectList(new LambdaQueryWrapper<BusinessLine>()
                .eq(BusinessLine::getStatus, 1));
        Map<Long, String> lineMode = lines.stream().collect(Collectors.toMap(BusinessLine::getId,
                line -> StringUtils.hasText(line.getRevenueMode()) ? line.getRevenueMode() : "full", (a, b) -> a));
        List<Project> projects = projectMapper.selectList(null);
        List<Long> enabledLineIds = lines.stream().map(BusinessLine::getId).toList();
        Map<Long, Long> wtToLocal = new HashMap<>();
        blMappingMapper.selectList(null).forEach(m -> wtToLocal.put(m.getWorktimeBusinessLineId(), m.getBusinessLineId()));
        return new AssignmentContext(lines, lineMode, projects, enabledLineIds, wtToLocal);
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

    // ------------------------------------------------------------------ JSON 字段解析

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

    private LocalDate dateText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            // 兼容 2026-08-07 / 2026-08-07T10:00:00
            return LocalDate.parse(text.trim().substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String monthText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 7 && trimmed.charAt(4) == '-') {
            return trimmed.substring(0, 7);
        }
        return null;
    }

    private record AssignmentContext(List<BusinessLine> lines, Map<Long, String> lineMode,
                                     List<Project> projects, List<Long> enabledLineIds,
                                     Map<Long, Long> wtToLocalLine) {
    }
}
