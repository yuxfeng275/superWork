package com.bu.management.plugin.brief;

import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.plugin.support.ProjectFactMessages;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.service.ProjectBriefService;
import com.bu.management.service.ProjectFactAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * save_daily_brief 的能力化实现：方法体与 AiAgentToolService#saveDailyBrief 逐字一致。
 *
 * <p>写操作：idempotent=false。briefDate 可显式指定——夜间 22:00 写的是「明天」的晨报，
 * 次日 07:30 按 brief_date=当天 推送，两边必须落在同一天。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "save_daily_brief",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.WRITE,
        title = "写晨报",
        whenToUse = "把今天该做的事写成当前用户的晨报（夜间跑完汇总用），一人一天一份，重复写入覆盖",
        inputSchema = """
                {"type":"object","properties":{"content":{"type":"string","description":"晨报正文 JSON 字符串：{\\\"items\\\":[{\\\"title\\\",\\\"why\\\",\\\"links\\\"}]}"},"projectId":{"type":"integer","description":"项目 ID，可选；不传写跨项目总晨报"},"briefDate":{"type":"string","description":"晨报日期 YYYY-MM-DD，可选，默认今天；夜间为「明天」准备时必传明天"},"taskId":{"type":"string","description":"agent-autoloop 任务号，可选，用于回溯"}}}
                """,
        timeoutMs = 30000,
        idempotent = false)
@Slf4j
@Component
@RequiredArgsConstructor
public class SaveDailyBriefCapability implements CapabilityHandler {

    private final ProjectBriefService projectBriefService;
    private final ProjectFactAccessGuard projectFactAccessGuard;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        Long userId = ctx.userId();
        String content = ToolArgs.text(input, "content");
        if (!StringUtils.hasText(content)) {
            return ToolResults.error("content 不能为空");
        }
        LocalDate briefDate = LocalDate.now();
        String rawDate = ToolArgs.text(input, "briefDate");
        if (StringUtils.hasText(rawDate)) {
            try {
                briefDate = LocalDate.parse(rawDate.strip());
            } catch (DateTimeParseException e) {
                return ToolResults.error("briefDate 格式无效，应为 YYYY-MM-DD");
            }
        }
        try {
            long projectId = input.path("projectId").asLong(0);
            if (projectId > 0 && !projectFactAccessGuard.canWrite(userId, projectId)) {
                log.warn("AI 工具 save_daily_brief 拒绝：用户 {} 对项目 {} 无写权限", userId, projectId);
                return ToolResults.error(ProjectFactMessages.NO_FACT_WRITE);
            }
            Long id = projectBriefService.save(userId, projectId <= 0 ? null : projectId,
                    briefDate, content, ToolArgs.text(input, "taskId"));
            return ToolResults.text("晨报已保存（id=" + id + "，日期 " + briefDate + "）");
        } catch (Exception e) {
            return ToolResults.error("晨报保存失败：" + e.getMessage());
        }
    }
}
