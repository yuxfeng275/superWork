package com.bu.management.plugin.opportunity;

import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.plugin.support.OpportunitySupport;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.plugin.support.ToolText;
import com.bu.management.service.OpportunityFollowUpService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * list_opportunity_followups_due 的能力化实现：
 * 方法体与 AiAgentToolService#listOpportunityFollowupsDue 逐字一致。
 *
 * <p>今日 / 逾期 / 静默三个桶，桶标签进每一行，逾期项直接告诉用户逾期了几天。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "list_opportunity_followups_due",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.READ,
        title = "商机跟进清单",
        whenToUse = "列出当前登录用户的商机跟进清单：今日待跟进、已逾期（带逾期天数）、长期未跟进；"
                + "逾期项直接告诉用户逾期了几天",
        inputSchema = """
                {"type":"object","properties":{"range":{"type":"string","description":"清单范围：today/overdue/silent/all，默认 all"},"silentDays":{"type":"integer","description":"silent 口径的静默天数，默认 7"},"limit":{"type":"integer","description":"返回条数上限，默认 10，最大 50"}}}
                """,
        timeoutMs = 15000)
@Component
@RequiredArgsConstructor
public class ListOpportunityFollowupsDueCapability implements CapabilityHandler {

    private final OpportunityFollowUpService opportunityFollowUpService;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        Long userId = ctx.userId();
        String rawRange = ToolArgs.text(input, "range");
        String range = StringUtils.hasText(rawRange) ? rawRange.strip().toLowerCase() : "all";
        if (!List.of("today", "overdue", "silent", "all").contains(range)) {
            return ToolResults.error("range 只能是 today/overdue/silent/all");
        }
        Integer silentDays = ToolArgs.integer(input, "silentDays");
        int days = silentDays == null ? OpportunitySupport.DEFAULT_SILENT_DAYS : Math.max(1, silentDays);
        LocalDate today = LocalDate.now();
        int cap = ToolArgs.limit(input);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        try {
            if ("today".equals(range) || "all".equals(range)) {
                OpportunitySupport.appendDueRows(rows, seen, "今日待跟进",
                        opportunityFollowUpService.dueToday(today), userId, today, cap,
                        opportunityFollowUpService);
            }
            if ("overdue".equals(range) || "all".equals(range)) {
                OpportunitySupport.appendDueRows(rows, seen, "已逾期",
                        opportunityFollowUpService.overdue(today), userId, today, cap,
                        opportunityFollowUpService);
            }
            if ("silent".equals(range) || "all".equals(range)) {
                OpportunitySupport.appendDueRows(rows, seen, "长期未跟进",
                        opportunityFollowUpService.silent(days), userId, today, cap,
                        opportunityFollowUpService);
            }
            if (rows.isEmpty()) {
                return ToolResults.text("当前用户名下没有"
                        + OpportunitySupport.rangeLabel(range) + "的商机");
            }
            return ToolResults.text(ToolText.render(userId, "商机跟进清单", rows));
        } catch (Exception e) {
            return ToolResults.error("跟进清单查询失败：" + e.getMessage());
        }
    }
}
