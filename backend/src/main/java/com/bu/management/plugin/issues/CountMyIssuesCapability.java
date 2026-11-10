package com.bu.management.plugin.issues;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.entity.Issue;
import com.bu.management.mapper.IssueMapper;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * count_my_issues 的能力化实现：方法体与 AiAgentToolService#countMyIssues 逐字一致。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "count_my_issues",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.READ,
        title = "统计我的事项",
        whenToUse = "统计当前登录用户负责的事项数量，可按状态过滤",
        inputSchema = """
                {"type":"object","properties":{"status":{"type":"string","description":"事项状态过滤"}}}
                """,
        timeoutMs = 15000)
@Component
@RequiredArgsConstructor
public class CountMyIssuesCapability implements CapabilityHandler {

    private final IssueMapper issueMapper;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        Long userId = ctx.userId();
        LambdaQueryWrapper<Issue> query = new LambdaQueryWrapper<Issue>()
                .eq(Issue::getAssigneeId, userId);
        String status = ToolArgs.text(input, "status");
        if (StringUtils.hasText(status)) {
            query.eq(Issue::getStatus, status);
        }
        Long count = issueMapper.selectCount(query);
        String scope = StringUtils.hasText(status) ? "（状态：" + status + "）" : "";
        return ToolResults.text("当前用户（ID=" + userId + "）共有 " + count + " 条事项" + scope);
    }
}
