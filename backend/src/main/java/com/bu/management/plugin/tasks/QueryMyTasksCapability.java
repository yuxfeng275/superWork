package com.bu.management.plugin.tasks;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.entity.Task;
import com.bu.management.mapper.TaskMapper;
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
 * query_my_tasks 的能力化实现：方法体与 AiAgentToolService#queryMyTasks 逐字一致。
 *
 * <p>只注入自己用得到的 TaskMapper——不把 AiAgentToolService 注进来，
 * 否则「注册表优先」和「旧 switch 兜底」会绕成环，回退也就没意义了。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "query_my_tasks",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.READ,
        title = "查询我的任务",
        whenToUse = "查询当前登录用户的任务列表，可按状态过滤",
        inputSchema = """
                {"type":"object","properties":{"status":{"type":"string","description":"任务状态过滤，如 待开始/进行中/已完成"},"limit":{"type":"integer","description":"返回条数上限，默认 10，最大 50"}}}
                """,
        timeoutMs = 15000)
@Component
@RequiredArgsConstructor
public class QueryMyTasksCapability implements CapabilityHandler {

    private final TaskMapper taskMapper;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        Long userId = ctx.userId();
        LambdaQueryWrapper<Task> query = new LambdaQueryWrapper<Task>()
                .eq(Task::getAssigneeId, userId)
                .orderByDesc(Task::getId);
        String status = ToolArgs.text(input, "status");
        if (StringUtils.hasText(status)) {
            query.eq(Task::getStatus, status);
        }
        List<Task> tasks = taskMapper.selectList(query.last("LIMIT " + ToolArgs.limit(input)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Task task : tasks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", task.getTitle());
            row.put("taskType", task.getTaskType());
            row.put("status", task.getStatus());
            row.put("estimatedHours", task.getEstimatedHours());
            row.put("actualHours", task.getActualHours());
            row.put("startDate", task.getStartDate());
            row.put("endDate", task.getEndDate());
            rows.add(row);
        }
        return ToolResults.text(ToolText.render(userId, "任务", rows));
    }
}
