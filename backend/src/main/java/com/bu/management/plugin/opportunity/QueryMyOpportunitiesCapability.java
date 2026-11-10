package com.bu.management.plugin.opportunity;

import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.plugin.support.OpportunitySupport;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.plugin.support.ToolText;
import com.bu.management.service.OpportunityFollowUpService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * query_my_opportunities 的能力化实现：方法体与 AiAgentToolService#queryMyOpportunities 逐字一致。
 *
 * <p>只返回负责人是当前用户的商机；空结果是带解释的空结果，不是无提示的空列表。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "query_my_opportunities",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.READ,
        title = "查询我的商机",
        whenToUse = "查询当前登录用户负责的商机，可按销售阶段和「超过几天没跟进」筛选；"
                + "回答「我手上哪些商机该跟进了」用这个",
        inputSchema = """
                {"type":"object","properties":{"stage":{"type":"string","description":"销售阶段过滤：初步接触/需求确认/方案报价/商务谈判/已成交，可选"},"silentDays":{"type":"integer","description":"只看超过 N 天没跟进的商机，可选（例如 7）"},"limit":{"type":"integer","description":"返回条数上限，默认 10，最大 50"}}}
                """,
        timeoutMs = 15000)
@Component
@RequiredArgsConstructor
public class QueryMyOpportunitiesCapability implements CapabilityHandler {

    private final OpportunityFollowUpService opportunityFollowUpService;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        Long userId = ctx.userId();
        String stage = ToolArgs.text(input, "stage");
        if (StringUtils.hasText(stage) && !OpportunitySupport.STAGES.contains(stage.strip())) {
            return ToolResults.error("stage 只能是：" + String.join("/", OpportunitySupport.STAGES));
        }
        Integer silentDays = ToolArgs.integer(input, "silentDays");
        try {
            List<SalesOpportunity> opportunities = opportunityFollowUpService.mine(
                    userId, stage, silentDays, ToolArgs.limit(input));
            List<Map<String, Object>> rows =
                    OpportunitySupport.opportunityRows(opportunities, opportunityFollowUpService);
            if (rows.isEmpty()) {
                return ToolResults.text("当前用户名下没有符合条件的商机"
                        + (StringUtils.hasText(stage) ? "（阶段：" + stage.strip() + "）" : "")
                        + (silentDays != null ? "（超过 " + silentDays + " 天没有跟进）" : "")
                        + "；不要猜测，可以让用户确认负责人口径");
            }
            return ToolResults.text(ToolText.render(userId, "商机", rows));
        } catch (Exception e) {
            return ToolResults.error("商机查询失败：" + e.getMessage());
        }
    }
}
