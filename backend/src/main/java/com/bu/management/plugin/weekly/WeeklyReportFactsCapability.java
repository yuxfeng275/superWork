package com.bu.management.plugin.weekly;

import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.plugin.support.WeekStarts;
import com.bu.management.service.WeeklyReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * weekly_report_facts 的能力化实现：方法体与 AiAgentToolService#weeklyReportFacts 逐字一致。
 *
 * <p>返回的是结构化 JSON（周报事实），走 {@link ToolResults#json(String)} 原样透传，
 * 不做文本包装——模型和侧车都按 JSON 解析它。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "weekly_report_facts",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.READ,
        title = "查询周报事实",
        whenToUse = "查询本周周报自动采集的事实（大事儿进度 + 财务数据 + 上周计划闭环）",
        inputSchema = """
                {"type":"object","properties":{"weekStart":{"type":"string","description":"本周周一 YYYY-MM-DD，可选，默认本周一"}}}
                """,
        timeoutMs = 30000)
@Component
@RequiredArgsConstructor
public class WeeklyReportFactsCapability implements CapabilityHandler {

    private final WeeklyReportService weeklyReportService;
    private final ObjectMapper objectMapper;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        try {
            LocalDate weekStart = WeekStarts.parseWeekStart(ToolArgs.text(input, "weekStart"));
            Map<String, Object> facts = weeklyReportService.collectFacts(weekStart);
            return ToolResults.json(objectMapper.writeValueAsString(facts));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return ToolResults.error("weekStart 格式无效，应为周一日期 YYYY-MM-DD");
        } catch (Exception e) {
            return ToolResults.error("周报事实采集失败：" + e.getMessage());
        }
    }
}
