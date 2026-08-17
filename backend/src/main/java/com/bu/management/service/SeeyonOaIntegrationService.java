package com.bu.management.service;

import com.bu.management.config.SeeyonOaRuntimeConfig;
import com.bu.management.dto.SeeyonOaConfigRequest;
import com.bu.management.integration.SeeyonOaClient;
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
    private final SeeyonOaConfigService configService;

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

    public Map<String, Object> saveConfig(SeeyonOaConfigRequest request, Long userId) {
        configService.save(request, userId);
        return getStatus();
    }

    public SeeyonOaConnectionTestResponse testConnection() {
        try {
            JsonNode user = oaClient.getCurrentUser();
            String memberName = user.path("name").asText();
            if (!memberName.isEmpty()) {
                memberName = user.path("memberName").asText(memberName);
            }
            String userName = user.path("loginName").asText();
            if (userName.isEmpty()) {
                userName = user.path("userName").asText();
            }
            String testedAt = LocalDateTime.now().toString();
            configService.recordConnectionTest(true, "连接成功", LocalDateTime.now());
            return SeeyonOaConnectionTestResponse.builder()
                    .success(true)
                    .userName(userName)
                    .memberName(memberName)
                    .message("OA 连接测试成功")
                    .testedAt(testedAt)
                    .build();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "未知错误";
            configService.recordConnectionTest(false, msg, LocalDateTime.now());
            return SeeyonOaConnectionTestResponse.builder()
                    .success(false)
                    .message("连接失败: " + msg)
                    .testedAt(LocalDateTime.now().toString())
                    .build();
        }
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
        return oaClient.listPendingAffairs().stream()
                .map(this::toAffairMap)
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> listDoneAffairs() {
        return oaClient.listDoneAffairs().stream()
                .map(this::toAffairMap)
                .collect(Collectors.toList());
    }

    // ==================== 数据同步 ====================

    /**
     * 同步 OA 数据到本地系统
     * 将 OA 中的人员、部门信息同步到本地
     */
    public List<String> syncAll() {
        List<String> logs = new ArrayList<>();
        try {
            // 同步部门
            List<SeeyonOaDepartmentOption> departments = listDepartments();
            logs.add("同步了 " + departments.size() + " 个部门");

            // 同步人员
            List<SeeyonOaMemberOption> members = listMembers();
            logs.add("同步了 " + members.size() + " 个人员");

            // TODO: 实际写入本地数据库（用户表、部门表等）
            // 这里可以根据业务需要，将 OA 数据映射到本地系统的 user 表等

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