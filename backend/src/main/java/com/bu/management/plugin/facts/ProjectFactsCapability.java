package com.bu.management.plugin.facts;

import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.entity.ProjectFact;
import com.bu.management.plugin.support.ProjectFactMessages;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.service.ProjectFactAccessGuard;
import com.bu.management.service.ProjectFactService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * project_facts 的能力化实现：方法体与 AiAgentToolService#projectFacts 逐字一致。
 *
 * <p>projectId 是模型给的，不可信，所以第一件事仍是 {@link ProjectFactAccessGuard#canRead} 准入；
 * 被拒时返回的文案与管理侧完全一致（{@link ProjectFactMessages#NO_FACT_READ}），
 * 不因为换了执行路径就变成「空事实」。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "project_facts",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.READ,
        title = "读取项目事实",
        whenToUse = "读取某个项目的统一事实（会议/邮件/云效工作项/大事儿），每条带出处，只能看到当前用户有权看的",
        inputSchema = """
                {"type":"object","properties":{"projectId":{"type":"integer","description":"项目 ID，必填"},"types":{"type":"string","description":"来源过滤，逗号分隔：MEETING,EMAIL,WORKITEM,KEY_MATTER，可选"},"sinceDays":{"type":"integer","description":"回看天数，默认 3，最大 30"},"limit":{"type":"integer","description":"返回条数上限，默认 10，最大 50"}}}
                """,
        timeoutMs = 30000)
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectFactsCapability implements CapabilityHandler {

    private final ProjectFactService projectFactService;
    private final ProjectFactAccessGuard projectFactAccessGuard;
    private final ObjectMapper objectMapper;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        Long userId = ctx.userId();
        long projectId = input.path("projectId").asLong(0);
        if (projectId <= 0) {
            return ToolResults.error("缺少 projectId");
        }
        if (!projectFactAccessGuard.canRead(userId, projectId)) {
            log.warn("AI 工具 project_facts 拒绝：用户 {} 对项目 {} 无读权限", userId, projectId);
            return ToolResults.error(ProjectFactMessages.NO_FACT_READ);
        }
        List<String> types = new ArrayList<>();
        String raw = ToolArgs.text(input, "types");
        if (StringUtils.hasText(raw)) {
            for (String part : raw.split(",")) {
                if (StringUtils.hasText(part)) {
                    types.add(part.strip().toUpperCase());
                }
            }
        }
        int days = Math.min(Math.max(input.path("sinceDays").asInt(3), 1), 30);
        List<ProjectFact> facts = projectFactService.queryForAgent(userId, projectId, types,
                LocalDateTime.now().minusDays(days), ToolArgs.limit(input));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProjectFact fact : facts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("factId", fact.getId());
            row.put("source", fact.getSourceType());
            row.put("ref", fact.getSourceRef());
            row.put("happenedAt", fact.getHappenedAt());
            row.put("title", fact.getTitle());
            row.put("digest", fact.getDigest());
            row.put("visibility", fact.getVisibility());
            rows.add(row);
        }
        try {
            if (rows.isEmpty()) {
                return ToolResults.json(objectMapper.writeValueAsString(Map.of(
                        "facts", rows,
                        "hint", "该项目在指定时间/类型范围内没有对当前用户可见的事实；"
                                + "不要猜测，换项目/放宽 sinceDays 或让用户补充材料")));
            }
            return ToolResults.json(objectMapper.writeValueAsString(Map.of("facts", rows)));
        } catch (Exception e) {
            return ToolResults.error("项目事实序列化失败：" + e.getMessage());
        }
    }
}
