package com.bu.management.plugin.requirements;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.entity.Requirement;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.plugin.support.ToolText;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * query_my_requirements 的能力化实现：方法体与 AiAgentToolService#queryMyRequirements 逐字一致。
 *
 * <p>口径仍是 creatorId（需求实体没有负责人字段），userId 缺失时直接返回空结果，
 * 绝不退化成全量查询——这条安全红线在能力路径上同样成立。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "query_my_requirements",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.READ,
        title = "查询我的需求",
        whenToUse = "查询当前登录用户负责的需求列表，可按状态过滤",
        inputSchema = """
                {"type":"object","properties":{"status":{"type":"string","description":"需求状态过滤"},"limit":{"type":"integer","description":"返回条数上限，默认 10，最大 50"}}}
                """,
        timeoutMs = 15000)
@Component
@RequiredArgsConstructor
public class QueryMyRequirementsCapability implements CapabilityHandler {

    private final RequirementMapper requirementMapper;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        Long userId = ctx.userId();
        if (userId == null) {
            return ToolResults.text("缺少当前用户上下文，未返回任何需求（我的需求仅限本人创建）");
        }
        LambdaQueryWrapper<Requirement> query = new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getCreatorId, userId)
                .orderByDesc(Requirement::getId);
        String status = ToolArgs.text(input, "status");
        if (StringUtils.hasText(status)) {
            query.eq(Requirement::getStatus, status);
        }
        List<Requirement> requirements = requirementMapper.selectList(
                query.last("LIMIT " + ToolArgs.limit(input)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Requirement requirement : requirements) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reqNo", requirement.getReqNo());
            row.put("title", requirement.getTitle());
            row.put("status", requirement.getStatus());
            row.put("priority", requirement.getPriority());
            row.put("expectedOnlineDate", requirement.getExpectedOnlineDate());
            row.put("actualOnlineDate", requirement.getActualOnlineDate());
            rows.add(row);
        }
        return ToolResults.text(ToolText.render(userId, "需求", rows));
    }
}
