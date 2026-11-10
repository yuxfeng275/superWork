package com.bu.management.plugin.worklogs;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.entity.WorkLog;
import com.bu.management.mapper.WorkLogMapper;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.plugin.support.ToolText;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * query_my_worklogs 的能力化实现：方法体与 AiAgentToolService#queryMyWorklogs 逐字一致。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "query_my_worklogs",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.READ,
        title = "查询我的工时",
        whenToUse = "查询当前登录用户的工时记录，可按日期范围过滤（YYYY-MM-DD）",
        inputSchema = """
                {"type":"object","properties":{"dateFrom":{"type":"string","description":"起始日期 YYYY-MM-DD"},"dateTo":{"type":"string","description":"结束日期 YYYY-MM-DD"},"limit":{"type":"integer","description":"返回条数上限，默认 10，最大 50"}}}
                """,
        timeoutMs = 15000)
@Component
@RequiredArgsConstructor
public class QueryMyWorklogsCapability implements CapabilityHandler {

    private final WorkLogMapper workLogMapper;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        Long userId = ctx.userId();
        LambdaQueryWrapper<WorkLog> query = new LambdaQueryWrapper<WorkLog>()
                .eq(WorkLog::getUserId, userId)
                .orderByDesc(WorkLog::getWorkDate);
        String dateFrom = ToolArgs.text(input, "dateFrom");
        String dateTo = ToolArgs.text(input, "dateTo");
        try {
            if (StringUtils.hasText(dateFrom)) {
                query.ge(WorkLog::getWorkDate, LocalDate.parse(dateFrom));
            }
            if (StringUtils.hasText(dateTo)) {
                query.le(WorkLog::getWorkDate, LocalDate.parse(dateTo));
            }
        } catch (DateTimeParseException e) {
            return ToolResults.error("日期格式无效，请使用 YYYY-MM-DD");
        }
        List<WorkLog> workLogs = workLogMapper.selectList(query.last("LIMIT " + ToolArgs.limit(input)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkLog workLog : workLogs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workDate", workLog.getWorkDate());
            row.put("hours", workLog.getHours());
            row.put("workContent", workLog.getWorkContent());
            row.put("taskId", workLog.getTaskId());
            rows.add(row);
        }
        return ToolResults.text(ToolText.render(userId, "工时", rows));
    }
}
