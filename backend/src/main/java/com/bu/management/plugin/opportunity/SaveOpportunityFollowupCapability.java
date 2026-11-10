package com.bu.management.plugin.opportunity;

import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.plugin.support.OpportunitySupport;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.service.OpportunityFollowUpParser;
import com.bu.management.service.OpportunityFollowUpService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * save_opportunity_followup 的能力化实现：
 * 方法体与 AiAgentToolService#saveOpportunityFollowup 逐字一致。
 *
 * <p>写操作：idempotent=false。阶段推进的前置条件由 Service 同一套守卫把守，
 * 违反时的业务异常文案原样回给模型（不吞成通用失败）。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "save_opportunity_followup",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.WRITE,
        title = "补记商机跟进",
        whenToUse = "给某个商机补一条跟进记录（聊了什么 + 阶段/概率/下次跟进时间），会同步刷新商机的阶段、"
                + "成交概率与下次跟进时间。写操作：正文必须先给用户确认再调用；"
                + "商机用上一步查询结果里的 opportunityId 指定",
        inputSchema = """
                {"type":"object","properties":{"opportunityId":{"type":"integer","description":"商机 ID（必填，取自 query_my_opportunities 的返回）"},"content":{"type":"string","description":"跟进内容，必填"},"stage":{"type":"string","description":"推进到的阶段，可选：初步接触/需求确认/方案报价/商务谈判/已成交"},"probability":{"type":"integer","description":"成交概率 0-100，可选"},"nextFollowUp":{"type":"string","description":"下次跟进时间，可选：明天 / 周三 15:00 / 2026-11-12 09:30"}}}
                """,
        timeoutMs = 30000,
        idempotent = false)
@Component
@RequiredArgsConstructor
public class SaveOpportunityFollowupCapability implements CapabilityHandler {

    private final OpportunityFollowUpService opportunityFollowUpService;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        Long userId = ctx.userId();
        long opportunityId = input.path("opportunityId").asLong(0);
        if (opportunityId <= 0) {
            return ToolResults.error("缺少 opportunityId（先用 query_my_opportunities 查到要记的商机）");
        }
        String content = ToolArgs.text(input, "content");
        if (!StringUtils.hasText(content)) {
            return ToolResults.error("content 不能为空（这条跟进聊了什么）");
        }
        String stage = ToolArgs.text(input, "stage");
        if (StringUtils.hasText(stage) && !OpportunitySupport.STAGES.contains(stage.strip())) {
            return ToolResults.error("stage 只能是：" + String.join("/", OpportunitySupport.STAGES));
        }
        Integer probability = ToolArgs.integer(input, "probability");
        if (probability != null && (probability < 0 || probability > 100)) {
            return ToolResults.error("probability 必须是 0-100 的整数");
        }
        LocalDateTime nextAt = null;
        String nextText = ToolArgs.text(input, "nextFollowUp");
        if (StringUtils.hasText(nextText)) {
            nextAt = OpportunityFollowUpParser.parseNextFollowUp(nextText, LocalDate.now());
            if (nextAt == null) {
                return ToolResults.error(
                        "下次跟进时间解析不出（可写「明天」「周三 15:00」「2026-11-12 09:30」），请先跟用户确认");
            }
        }
        try {
            SalesOpportunityFollowUp saved = opportunityFollowUpService.recordManualFollowUp(
                    opportunityId, userId, stage, probability, content, nextAt);
            SalesOpportunity opportunity = opportunityFollowUpService.get(opportunityId);
            return ToolResults.text("已记入商机「" + opportunity.getName() + "」的跟进：阶段 "
                    + saved.getStatus() + "，概率 " + saved.getProbability()
                    + (StringUtils.hasText(saved.getNextFollowUp()) ? "，下次跟进 " + saved.getNextFollowUp() : ""));
        } catch (Exception e) {
            // 阶段守卫等业务异常原样回给模型，不吞成通用失败
            return ToolResults.error(StringUtils.hasText(e.getMessage())
                    ? e.getMessage() : "跟进记录保存失败");
        }
    }
}
