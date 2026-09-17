package com.bu.management.service;

import com.bu.management.config.SeeyonOaRuntimeConfig;
import com.bu.management.integration.SeeyonOaClient;
import com.bu.management.integration.SeeyonOaWebChannel;
import com.bu.management.vo.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeeyonOaIntegrationService {

    private final SeeyonOaClient oaClient;
    private final SeeyonOaWebChannel webChannel;
    private final SeeyonOaConfigService configService;
    private final com.bu.management.sync.SyncOrchestrator syncOrchestrator;

    // ==================== 配置管理 ====================

    public Map<String, Object> getStatus() {
        SeeyonOaRuntimeConfig config = configService.getRuntimeConfig();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", config.enabled());
        status.put("baseUrl", config.baseUrl());
        status.put("hasCredentials", config.hasCredentials());
        status.put("tokenSource", config.tokenSource());
        status.put("lastTestedAt", config.lastTestedAt());
        status.put("lastTestStatus", config.lastTestStatus());
        status.put("lastTestMessage", config.lastTestMessage());
        return status;
    }

    // ==================== 数据查询 ====================

    public List<SeeyonOaMemberOption> listMembers() {
        return oaClient.listMembers().stream()
                .map(this::toMemberOption)
                .collect(Collectors.toList());
    }

    public List<SeeyonOaDepartmentOption> listDepartments() {
        return oaClient.listDepartments().stream()
                .map(this::toDepartmentOption)
                .collect(Collectors.toList());
    }

    public List<SeeyonOaMemberOption> listMembersByDepartment(String departmentId) {
        return oaClient.listMembersByDepartment(departmentId).stream()
                .map(this::toMemberOption)
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> listPendingAffairs() {
        try {
            return oaClient.listPendingAffairs().stream()
                    .map(this::toAffairMap)
                    .collect(Collectors.toList());
        } catch (IllegalStateException e) {
            // REST 被网关/策略拦截 → 网页会话通道（已验证与页面同一数据源）
            log.info("OA REST 待办不可用，走网页会话通道: {}", e.getMessage());
            return webChannel.listPendingAffairs().stream()
                    .map(this::toWebAffairMap)
                    .collect(Collectors.toList());
        }
    }

    public List<Map<String, Object>> listDoneAffairs() {
        try {
            return oaClient.listDoneAffairs().stream()
                    .map(this::toAffairMap)
                    .collect(Collectors.toList());
        } catch (IllegalStateException e) {
            log.info("OA REST 已办不可用，走网页会话通道: {}", e.getMessage());
            return webChannel.listDoneAffairs().stream()
                    .map(this::toWebAffairMap)
                    .collect(Collectors.toList());
        }
    }

    private Map<String, Object> toWebAffairMap(SeeyonOaWebChannel.WebAffair affair) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", affair.affairId());
        map.put("subject", affair.title());
        map.put("senderName", affair.sender());
        map.put("createDate", affair.receiveTime());
        map.put("appName", affair.type());
        map.put("state", affair.type());
        map.put("flowId", affair.affairId());
        map.put("formId", null);
        map.put("linkUrl", affair.linkUrl());
        return map;
    }

    // ==================== 授权与审批 ====================

    public SeeyonOaWebChannel.SessionStatus sessionStatus() {
        return webChannel.sessionStatus();
    }

    public SeeyonOaWebChannel.SessionStatus authorize(String cookie) {
        return webChannel.authorize(cookie);
    }

    public void clearSession() {
        webChannel.clearSession();
    }

    /** 单项审批（approve=同意 / reject=不同意）。 */
    public String approve(String affairId, String action) {
        return webChannel.actOnAffair(affairId, action);
    }

    /** 批量审批：逐项执行，返回每项结果。 */
    public List<Map<String, Object>> batchApprove(List<String> affairIds, String action) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String affairId : affairIds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("affairId", affairId);
            try {
                row.put("result", approve(affairId, action));
                row.put("success", true);
            } catch (IllegalStateException e) {
                row.put("success", false);
                row.put("result", e.getMessage());
            }
            results.add(row);
        }
        return results;
    }

    // ==================== 数据同步 ====================

    /**
     * 同步 OA 数据到本地系统
     * 组织/人员落库已迁入统一同步编排（oa-org 任务 → oa_org_department / oa_org_member 表），
     * 本入口保留给旧前端，内部委托统一编排器执行并写入 data_sync_log。
     */
    public List<String> syncAll() {
        List<String> logs = new ArrayList<>();
        try {
            List<com.bu.management.entity.DataSyncLog> entries = syncOrchestrator.run(
                    com.bu.management.sync.collector.OaOrgCollector.TASK_CODE, null, "manual", null);
            for (com.bu.management.entity.DataSyncLog entry : entries) {
                logs.add(entry.getDomain() + ": " + entry.getStatus()
                        + (entry.getUpsertCount() != null ? "（写入 " + entry.getUpsertCount() + " 条）" : ""));
            }
            if (entries.isEmpty()) {
                logs.add("OA 集成未配置，跳过同步");
            }
            log.info("OA 数据同步完成: {}", logs);
        } catch (Exception e) {
            log.error("OA 数据同步失败", e);
            logs.add("同步失败: " + e.getMessage());
        }
        return logs;
    }

    // ==================== 私有转换方法 ====================

    private SeeyonOaMemberOption toMemberOption(JsonNode node) {
        return SeeyonOaMemberOption.builder()
                .id(node.path("id").asText())
                .name(node.path("name").asText())
                .loginName(node.path("loginName").asText())
                .departmentName(node.path("departmentName").asText())
                .email(node.path("email").asText())
                .mobile(node.path("mobile").asText())
                .enabled(node.path("enabled").asBoolean(true))
                .build();
    }

    private SeeyonOaDepartmentOption toDepartmentOption(JsonNode node) {
        return SeeyonOaDepartmentOption.builder()
                .id(node.path("id").asText())
                .name(node.path("name").asText())
                .parentId(node.path("parentId").asText())
                .parentName(node.path("parentName").asText())
                .sortOrder(node.path("sortOrder").asInt(0))
                .enabled(node.path("enabled").asBoolean(true))
                .build();
    }

    private Map<String, Object> toAffairMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", node.path("id").asText());
        map.put("subject", node.path("subject").asText());
        map.put("senderName", node.path("senderName").asText());
        map.put("createDate", node.path("createDate").asText());
        map.put("appName", node.path("appName").asText());
        map.put("state", node.path("state").asText());
        map.put("flowId", node.path("flowId").asText());
        map.put("formId", node.path("formId").asText());
        return map;
    }
}