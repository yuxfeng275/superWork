package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Issue;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.Task;
import com.bu.management.entity.WorkLog;
import com.bu.management.mapper.IssueMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.WorkLogMapper;
import com.bu.management.vo.AiAgentToolDefinition;
import com.bu.management.vo.AiAgentToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 助手只读工具：查询当前用户的任务/需求/工时/事项。
 * 侧车回调只带 runId，用户身份由控制器在发起运行前注册（runId→userId）。
 * 任何失败都转换为 isError 结果返回给模型，绝不向侧车抛异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentToolService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final TaskMapper taskMapper;
    private final RequirementMapper requirementMapper;
    private final WorkLogMapper workLogMapper;
    private final IssueMapper issueMapper;
    private final ObjectMapper objectMapper;
    private final ConnectorToolService connectorToolService;
    private final GenericConnectorToolService genericConnectorToolService;

    /**
     * 连接器工具名集合；execute 命中时委托 ConnectorToolService。
     */
    private static final Set<String> CONNECTOR_TOOLS = Set.of(
            "search_my_emails", "read_my_email",
            "query_yunxiao_projects", "query_yunxiao_workitems",
            "query_oa_pending", "query_oa_done", "get_oa_flow",
            "search_yuque_docs", "read_yuque_doc", "query_my_worktime");

    /**
     * 运行期 runId→userId 注册表；运行结束/超时后由控制器移除。
     */
    private final ConcurrentHashMap<String, Long> runUsers = new ConcurrentHashMap<>();

    public void registerRun(String runId, Long userId) {
        runUsers.put(runId, userId);
    }

    public void completeRun(String runId) {
        runUsers.remove(runId);
    }

    /**
     * 工具定义；parameters 为 JSON Schema 对象。
     */
    public List<AiAgentToolDefinition> definitions() {
        List<AiAgentToolDefinition> defs = new ArrayList<>();
        defs.add(new AiAgentToolDefinition("query_my_tasks", "查询当前登录用户的任务列表，可按状态过滤",
                objectSchema(Map.of(
                        "status", stringProperty("任务状态过滤，如 待开始/进行中/已完成"),
                        "limit", integerProperty("返回条数上限，默认 10，最大 50")))));
        defs.add(new AiAgentToolDefinition("query_my_requirements", "查询当前登录用户负责的需求列表，可按状态过滤",
                objectSchema(Map.of(
                        "status", stringProperty("需求状态过滤"),
                        "limit", integerProperty("返回条数上限，默认 10，最大 50")))));
        defs.add(new AiAgentToolDefinition("query_my_worklogs", "查询当前登录用户的工时记录，可按日期范围过滤（YYYY-MM-DD）",
                objectSchema(Map.of(
                        "dateFrom", stringProperty("起始日期 YYYY-MM-DD"),
                        "dateTo", stringProperty("结束日期 YYYY-MM-DD"),
                        "limit", integerProperty("返回条数上限，默认 10，最大 50")))));
        defs.add(new AiAgentToolDefinition("count_my_issues", "统计当前登录用户负责的事项数量，可按状态过滤",
                objectSchema(Map.of(
                        "status", stringProperty("事项状态过滤")))));
        defs.addAll(connectorToolService.definitions());
        defs.addAll(genericConnectorToolService.definitions());
        return defs;
    }

    /**
     * 执行工具；未知 runId/未知工具/参数错误均返回 isError 结果，不抛异常。
     */
    public AiAgentToolResult execute(String runId, String toolName, String argsJson) {
        Long userId = runUsers.get(runId);
        if (userId == null) {
            return new AiAgentToolResult("无效或已过期的运行 ID", true);
        }
        try {
            JsonNode args = StringUtils.hasText(argsJson)
                    ? objectMapper.readTree(argsJson) : objectMapper.createObjectNode();
            if (args == null || !args.isObject()) {
                args = objectMapper.createObjectNode();
            }
            return switch (toolName) {
                case "query_my_tasks" -> queryMyTasks(userId, args);
                case "query_my_requirements" -> queryMyRequirements(userId, args);
                case "query_my_worklogs" -> queryMyWorklogs(userId, args);
                case "count_my_issues" -> countMyIssues(userId, args);
                default -> connectorToolService.handles(toolName)
                        ? connectorToolService.execute(userId, toolName, args)
                        : genericConnectorToolService.execute(toolName, args);
            };
        } catch (Exception e) {
            log.warn("AI 工具执行失败: tool={}, error={}", toolName, e.getMessage());
            return new AiAgentToolResult("工具执行失败：" + e.getMessage(), true);
        }
    }

    private AiAgentToolResult queryMyTasks(Long userId, JsonNode args) {
        LambdaQueryWrapper<Task> query = new LambdaQueryWrapper<Task>()
                .eq(Task::getAssigneeId, userId)
                .orderByDesc(Task::getId);
        String status = textArg(args, "status");
        if (StringUtils.hasText(status)) {
            query.eq(Task::getStatus, status);
        }
        List<Task> tasks = taskMapper.selectList(query.last("LIMIT " + limit(args)));
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
        return new AiAgentToolResult(render(userId, "任务", rows), false);
    }

    private AiAgentToolResult queryMyRequirements(Long userId, JsonNode args) {
        LambdaQueryWrapper<Requirement> query = new LambdaQueryWrapper<Requirement>()
                .orderByDesc(Requirement::getId);
        String status = textArg(args, "status");
        if (StringUtils.hasText(status)) {
            query.eq(Requirement::getStatus, status);
        }
        List<Requirement> requirements = requirementMapper.selectList(query.last("LIMIT " + limit(args)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Requirement requirement : requirements) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reqNo", requirement.getReqNo());
            row.put("title", requirement.getTitle());
            row.put("status", requirement.getStatus());
            row.put("priority", requirement.getPriority());
            row.put("expectedOnlineDate", requirement.getExpectedOnlineDate());
            row.put("actualOnlineDate", requirement.getActualOnlineDate());
            rows.add(row);
        }
        return new AiAgentToolResult(render(userId, "需求", rows), false);
    }

    private AiAgentToolResult queryMyWorklogs(Long userId, JsonNode args) {
        LambdaQueryWrapper<WorkLog> query = new LambdaQueryWrapper<WorkLog>()
                .eq(WorkLog::getUserId, userId)
                .orderByDesc(WorkLog::getWorkDate);
        String dateFrom = textArg(args, "dateFrom");
        String dateTo = textArg(args, "dateTo");
        try {
            if (StringUtils.hasText(dateFrom)) {
                query.ge(WorkLog::getWorkDate, LocalDate.parse(dateFrom));
            }
            if (StringUtils.hasText(dateTo)) {
                query.le(WorkLog::getWorkDate, LocalDate.parse(dateTo));
            }
        } catch (DateTimeParseException e) {
            return new AiAgentToolResult("日期格式无效，请使用 YYYY-MM-DD", true);
        }
        List<WorkLog> workLogs = workLogMapper.selectList(query.last("LIMIT " + limit(args)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkLog workLog : workLogs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workDate", workLog.getWorkDate());
            row.put("hours", workLog.getHours());
            row.put("workContent", workLog.getWorkContent());
            row.put("taskId", workLog.getTaskId());
            rows.add(row);
        }
        return new AiAgentToolResult(render(userId, "工时", rows), false);
    }

    private AiAgentToolResult countMyIssues(Long userId, JsonNode args) {
        LambdaQueryWrapper<Issue> query = new LambdaQueryWrapper<Issue>()
                .eq(Issue::getAssigneeId, userId);
        String status = textArg(args, "status");
        if (StringUtils.hasText(status)) {
            query.eq(Issue::getStatus, status);
        }
        Long count = issueMapper.selectCount(query);
        String scope = StringUtils.hasText(status) ? "（状态：" + status + "）" : "";
        return new AiAgentToolResult("当前用户（ID=" + userId + "）共有 " + count + " 条事项" + scope, false);
    }

    private String textArg(JsonNode args, String name) {
        JsonNode node = args.path(name);
        return node.isTextual() ? node.asText() : null;
    }

    private int limit(JsonNode args) {
        JsonNode node = args.path("limit");
        if (!node.canConvertToInt()) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(node.asInt(), 1), MAX_LIMIT);
    }

    private String render(Long userId, String label, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "当前用户（ID=" + userId + "）暂无符合条件的" + label + "记录";
        }
        StringBuilder sb = new StringBuilder("当前用户（ID=" + userId + "）的" + label + "，共 "
                + rows.size() + " 条：");
        for (Map<String, Object> row : rows) {
            sb.append("\n- ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                if (!first) {
                    sb.append("，");
                }
                sb.append(entry.getKey()).append("=").append(value);
                first = false;
            }
        }
        return sb.toString();
    }

    private JsonNode objectSchema(Map<String, JsonNode> properties) {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        properties.forEach(props::set);
        return schema;
    }

    private JsonNode stringProperty(String description) {
        var node = objectMapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private JsonNode integerProperty(String description) {
        var node = objectMapper.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }
}
