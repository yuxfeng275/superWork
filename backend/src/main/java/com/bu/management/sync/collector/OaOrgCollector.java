package com.bu.management.sync.collector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.OaOrgDepartment;
import com.bu.management.entity.OaOrgMember;
import com.bu.management.integration.SeeyonOaClient;
import com.bu.management.mapper.OaOrgDepartmentMapper;
import com.bu.management.mapper.OaOrgMemberMapper;
import com.bu.management.service.SeeyonOaConfigService;
import com.bu.management.sync.SyncOutcome;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OA 组织/人员采集器：全量覆盖语义 —— 按 oa_id upsert，本次未出现的标记 enabled=0（不物理删除）。
 * 产出 org / member 两个域的结果。
 */
@Component
@RequiredArgsConstructor
public class OaOrgCollector implements DataCollector {

    public static final String TASK_CODE = "oa-org";

    private final SeeyonOaClient oaClient;
    private final SeeyonOaConfigService configService;
    private final OaOrgDepartmentMapper departmentMapper;
    private final OaOrgMemberMapper memberMapper;

    @Override
    public String taskCode() {
        return TASK_CODE;
    }

    @Override
    @Transactional
    public List<SyncOutcome> collect(String scope, String triggeredBy) {
        if (!configService.getRuntimeConfig().isConfigured()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<SyncOutcome> outcomes = new ArrayList<>();
        outcomes.add(syncDepartments(now));
        outcomes.add(syncMembers(now));
        return outcomes;
    }

    private SyncOutcome syncDepartments(LocalDateTime now) {
        List<JsonNode> nodes = oaClient.listDepartments();
        Set<String> seen = new HashSet<>();
        int upserted = 0;
        for (JsonNode node : nodes) {
            String oaId = node.path("id").asText(null);
            if (oaId == null || oaId.isBlank()) {
                continue;
            }
            seen.add(oaId);
            OaOrgDepartment row = departmentMapper.selectOne(
                    new LambdaQueryWrapper<OaOrgDepartment>()
                            .eq(OaOrgDepartment::getOaId, oaId));
            if (row == null) {
                row = new OaOrgDepartment();
                row.setOaId(oaId);
            }
            row.setName(node.path("name").asText(""));
            row.setParentOaId(node.path("parentId").asText(null));
            row.setParentName(node.path("parentName").asText(null));
            row.setSortOrder(node.path("sortOrder").isNumber() ? node.path("sortOrder").asInt() : null);
            row.setEnabled(node.path("enabled").asBoolean(true) ? 1 : 0);
            row.setSyncedAt(now);
            if (row.getId() == null) {
                departmentMapper.insert(row);
            } else {
                departmentMapper.updateById(row);
            }
            upserted++;
        }
        int deactivated = deactivateMissingDepartments(seen, now);
        return SyncOutcome.success("org", "all", nodes.size(), upserted, null,
                "部门同步完成，失效标记 " + deactivated + " 个");
    }

    private SyncOutcome syncMembers(LocalDateTime now) {
        List<JsonNode> nodes = oaClient.listMembers();
        Set<String> seen = new HashSet<>();
        int upserted = 0;
        for (JsonNode node : nodes) {
            String oaId = node.path("id").asText(null);
            if (oaId == null || oaId.isBlank()) {
                continue;
            }
            seen.add(oaId);
            OaOrgMember row = memberMapper.selectOne(
                    new LambdaQueryWrapper<OaOrgMember>()
                            .eq(OaOrgMember::getOaId, oaId));
            if (row == null) {
                row = new OaOrgMember();
                row.setOaId(oaId);
            }
            row.setName(node.path("name").asText(""));
            row.setLoginName(node.path("loginName").asText(null));
            row.setDepartmentOaId(node.path("departmentId").asText(null));
            row.setDepartmentName(node.path("departmentName").asText(null));
            row.setEmail(node.path("email").asText(null));
            row.setMobile(node.path("mobile").asText(null));
            row.setEnabled(node.path("enabled").asBoolean(true) ? 1 : 0);
            row.setSyncedAt(now);
            if (row.getId() == null) {
                memberMapper.insert(row);
            } else {
                memberMapper.updateById(row);
            }
            upserted++;
        }
        int deactivated = deactivateMissingMembers(seen, now);
        return SyncOutcome.success("member", "all", nodes.size(), upserted, null,
                "人员同步完成，失效标记 " + deactivated + " 人");
    }

    private int deactivateMissingDepartments(Set<String> seen, LocalDateTime now) {
        List<OaOrgDepartment> stale = departmentMapper.selectList(
                new LambdaQueryWrapper<OaOrgDepartment>()
                        .eq(OaOrgDepartment::getEnabled, 1)
                        .notIn(!seen.isEmpty(), OaOrgDepartment::getOaId, seen));
        for (OaOrgDepartment row : stale) {
            row.setEnabled(0);
            row.setSyncedAt(now);
            departmentMapper.updateById(row);
        }
        return stale.size();
    }

    private int deactivateMissingMembers(Set<String> seen, LocalDateTime now) {
        List<OaOrgMember> stale = memberMapper.selectList(
                new LambdaQueryWrapper<OaOrgMember>()
                        .eq(OaOrgMember::getEnabled, 1)
                        .notIn(!seen.isEmpty(), OaOrgMember::getOaId, seen));
        for (OaOrgMember row : stale) {
            row.setEnabled(0);
            row.setSyncedAt(now);
            memberMapper.updateById(row);
        }
        return stale.size();
    }
}
