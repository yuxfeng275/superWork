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
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * weekly_report_input 的能力化实现：方法体与 AiAgentToolService#weeklyReportInput 逐字一致。
 *
 * <p>风险等级按既定迁移口径记为 READ（与旧 switch 里的定位一致），
 * 写门禁仍由 AiAgentToolService 在调用前统一把守，能力路径不改变那层判断顺序。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "weekly_report_input",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.READ,
        title = "写入周报草稿",
        whenToUse = "将对话中的内容写入本周周报草稿（企微智能总结或补充信息）",
        inputSchema = """
                {"type":"object","properties":{"wecomSummary":{"type":"string","description":"企微智能总结内容，可选"},"manualNotes":{"type":"string","description":"人为补充信息，可选"}}}
                """,
        timeoutMs = 30000)
@Component
@RequiredArgsConstructor
public class WeeklyReportInputCapability implements CapabilityHandler {

    private final WeeklyReportService weeklyReportService;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        String wecomSummary = ToolArgs.text(input, "wecomSummary");
        String manualNotes = ToolArgs.text(input, "manualNotes");
        if (!StringUtils.hasText(wecomSummary) && !StringUtils.hasText(manualNotes)) {
            return ToolResults.error("请提供 wecomSummary 或 manualNotes 至少一项");
        }
        try {
            LocalDate weekStart = WeekStarts.parseWeekStart(ToolArgs.text(input, "weekStart"));
            var report = weeklyReportService.getOrCreate(weekStart);
            weeklyReportService.saveInputs(report.getId(), wecomSummary, manualNotes, null);
            return ToolResults.text("已写入本周（" + weekStart + "）周报草稿："
                    + (StringUtils.hasText(wecomSummary) ? "企微智能总结 " : "")
                    + (StringUtils.hasText(manualNotes) ? "补充信息" : ""));
        } catch (Exception e) {
            return ToolResults.error("写入周报失败：" + e.getMessage());
        }
    }
}
