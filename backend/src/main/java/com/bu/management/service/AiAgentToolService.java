package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiArtifact;
import com.bu.management.entity.Issue;
import com.bu.management.entity.ProjectFact;
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
import java.time.LocalDateTime;
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
    private final EmailActionToolService emailActionToolService;
    private final WeComCliToolService weComCliToolService;
    private final WeeklyReportService weeklyReportService;
    private final JevDecisionService jevDecisionService;
    private final ProjectFactService projectFactService;
    private final ProjectBriefService projectBriefService;
    private final AiArtifactService aiArtifactService;

    /**
     * 连接器工具名集合；execute 命中时委托 ConnectorToolService。
     */
    private static final Set<String> CONNECTOR_TOOLS = Set.of(
            "search_my_emails", "read_my_email",
            "query_yunxiao_projects", "query_yunxiao_workitems",
            "query_oa_pending", "query_oa_done", "get_oa_flow",
            "search_yuque_docs", "read_yuque_doc", "query_my_worktime");

    /**
     * 夜间自动化运行可见的工具：项目事实 + 写晨报 + 只读查询。
     * 发信、企微消息、待办、周报写入等对外/改状态的工具一律不下发。
     */
    static final Set<String> AUTOMATION_TOOLS = Set.of(
            "project_facts", "save_daily_brief", "save_artifact",
            "query_my_tasks", "query_my_requirements", "query_my_worklogs", "count_my_issues",
            "weekly_report_facts");

    /** 无人值守运行里允许绕过写门禁的工具：只能写给自己看、不触达外部。 */
    static final Set<String> AUTOMATION_SAFE_WRITE_TOOLS = Set.of("save_daily_brief");

    /**
     * 运行期 runId→userId 注册表；运行结束/超时后由控制器移除。
     */
    private final ConcurrentHashMap<String, Long> runUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> runMessages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> runUnattended = new ConcurrentHashMap<>();

    public void registerRun(String runId, Long userId) {
        registerRun(runId, userId, null);
    }

    public void registerRun(String runId, Long userId, String userMessage) {
        registerRun(runId, userId, userMessage, false);
    }

    /**
     * 注册运行身份；unattended=true 表示「无人在场」（agent-autoloop 夜间任务），
     * 这类运行有独立的工具白名单与门禁策略，见 {@link #AUTOMATION_TOOLS}。
     */
    public void registerRun(String runId, Long userId, String userMessage, boolean unattended) {
        runUsers.put(runId, userId);
        if (StringUtils.hasText(userMessage)) {
            runMessages.put(runId, userMessage);
        }
        if (unattended) {
            runUnattended.put(runId, Boolean.TRUE);
        }
    }

    public void completeRun(String runId) {
        runUsers.remove(runId);
        runMessages.remove(runId);
        runUnattended.remove(runId);
    }

    /**
     * 工具定义；parameters 为 JSON Schema 对象。
     */
    public List<AiAgentToolDefinition> definitions() {
        return definitions(null);
    }

    /** 可按 Jev 意图决策收窄工具集；decision 为空则返回全量。 */
    public List<AiAgentToolDefinition> definitions(JevDecisionService.IntentDecision decision) {
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
        defs.add(new AiAgentToolDefinition("weekly_report_facts",
                "查询本周周报自动采集的事实（大事儿进度 + 财务数据 + 上周计划闭环）",
                objectSchema(Map.of(
                        "weekStart", stringProperty("本周周一 YYYY-MM-DD，可选，默认本周一")))));
        defs.add(new AiAgentToolDefinition("weekly_report_input",
                "将对话中的内容写入本周周报草稿（企微智能总结或补充信息）",
                objectSchema(Map.of(
                        "wecomSummary", stringProperty("企微智能总结内容，可选"),
                        "manualNotes", stringProperty("人为补充信息，可选")))));
        defs.add(new AiAgentToolDefinition("project_facts",
                "读取某个项目的统一事实（会议/邮件/云效工作项/大事儿），每条带出处，只能看到当前用户有权看的",
                objectSchema(Map.of(
                        "projectId", integerProperty("项目 ID，必填"),
                        "types", stringProperty("来源过滤，逗号分隔：MEETING,EMAIL,WORKITEM,KEY_MATTER，可选"),
                        "sinceDays", integerProperty("回看天数，默认 3，最大 30"),
                        "limit", integerProperty("返回条数上限，默认 10，最大 50")))));
        defs.add(new AiAgentToolDefinition("save_daily_brief",
                "把今天该做的事写成当前用户的晨报（夜间跑完汇总用），一人一天一份，重复写入覆盖",
                objectSchema(Map.of(
                        "content", stringProperty("晨报正文 JSON 字符串：{\"items\":[{\"title\",\"why\",\"links\"}]}"),
                        "projectId", integerProperty("项目 ID，可选；不传写跨项目总晨报"),
                        "briefDate", stringProperty("晨报日期 YYYY-MM-DD，可选，默认今天；夜间为「明天」准备时必传明天"),
                        "taskId", stringProperty("agent-autoloop 任务号，可选，用于回溯")))));
        defs.add(new AiAgentToolDefinition("save_artifact",
                "把夜间产出的制品（方案/补丁说明/修复说明/审查意见处理/清单）存成待评审草稿；"
                        + "必须挂证据（project_fact.id 列表），无证据的制品不进评审队列",
                objectSchema(Map.of(
                        "projectId", integerProperty("项目 ID，必填"),
                        "title", stringProperty("制品标题，必填"),
                        "kind", stringProperty("制品类型，必填：PLAN/PATCH/FIX_NOTE/REVIEW_FIX/CHECKLIST"),
                        "content", stringProperty("制品正文（方案正文/补丁说明/diff 摘要），必填"),
                        "evidence", stringProperty("证据 JSON 数组字符串：project_fact.id 列表，如 [12,15]，必填"),
                        "taskId", stringProperty("agent-autoloop 任务号，可选，用于回溯")))));
        defs.addAll(connectorToolService.definitions());
        defs.addAll(genericConnectorToolService.definitions());
        defs.addAll(emailActionToolService.definitions());
        defs.addAll(weComCliToolService.definitions());
        return jevDecisionService.filterTools(decision, defs);
    }

    /**
     * 夜间无人值守运行下发的工具白名单（代码护栏，不依赖提示词）。
     * 与 {@link #execute} 里的运行时校验是双保险：即使侧车硬调白名单外的工具也会被拒。
     */
    public List<AiAgentToolDefinition> automationDefinitions() {
        return definitions(null).stream()
                .filter(tool -> AUTOMATION_TOOLS.contains(tool.name()))
                .toList();
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
            boolean unattended = Boolean.TRUE.equals(runUnattended.get(runId));
            if (unattended && !AUTOMATION_TOOLS.contains(toolName)) {
                return new AiAgentToolResult("夜间无人值守运行不允许调用该工具：" + toolName, true);
            }
            // 无人值守运行里只有「写给自己」的工具免门禁；人在对话中的写操作照旧过 Jev 门禁。
            if (!unattended || !AUTOMATION_SAFE_WRITE_TOOLS.contains(toolName)) {
                JevDecisionService.WriteGate gate = jevDecisionService.gateWrite(
                        toolName, runMessages.get(runId), args.toString());
                if (!gate.allow()) {
                    return new AiAgentToolResult(gate.reason(), true);
                }
            }
            return switch (toolName) {
                case "query_my_tasks" -> queryMyTasks(userId, args);
                case "query_my_requirements" -> queryMyRequirements(userId, args);
                case "query_my_worklogs" -> queryMyWorklogs(userId, args);
                case "count_my_issues" -> countMyIssues(userId, args);
                case "weekly_report_facts" -> weeklyReportFacts(args);
                case "weekly_report_input" -> weeklyReportInput(args);
                case "project_facts" -> projectFacts(userId, args);
                case "save_daily_brief" -> saveDailyBrief(userId, args);
                case "save_artifact" -> saveArtifact(userId, args);
                default -> connectorToolService.handles(toolName)
                        ? connectorToolService.execute(userId, toolName, args)
                        : emailActionToolService.handles(toolName)
                        ? emailActionToolService.execute(userId, toolName, args)
                        : weComCliToolService.handles(toolName)
                        ? weComCliToolService.execute(userId, toolName, args)
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

    /** 项目事实：按当前用户可见性过滤后返回；正文只给摘要，原文不进上下文。 */
    private AiAgentToolResult projectFacts(Long userId, JsonNode args) {
        long projectId = args.path("projectId").asLong(0);
        if (projectId <= 0) {
            return new AiAgentToolResult("缺少 projectId", true);
        }
        List<String> types = new ArrayList<>();
        String raw = textArg(args, "types");
        if (StringUtils.hasText(raw)) {
            for (String part : raw.split(",")) {
                if (StringUtils.hasText(part)) {
                    types.add(part.strip().toUpperCase());
                }
            }
        }
        int days = Math.min(Math.max(args.path("sinceDays").asInt(3), 1), 30);
        List<ProjectFact> facts = projectFactService.queryForAgent(userId, projectId, types,
                LocalDateTime.now().minusDays(days), limit(args));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProjectFact fact : facts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("factId", fact.getId());
            row.put("source", fact.getSourceType());
            row.put("ref", fact.getSourceRef());
            row.put("happenedAt", fact.getHappenedAt());
            row.put("title", fact.getTitle());
            row.put("digest", fact.getDigest());
            row.put("visibility", fact.getVisibility());
            rows.add(row);
        }
        try {
            return new AiAgentToolResult(objectMapper.writeValueAsString(Map.of("facts", rows)), false);
        } catch (Exception e) {
            return new AiAgentToolResult("项目事实序列化失败：" + e.getMessage(), true);
        }
    }

    /**
     * 写晨报：只写当前用户自己那份；重复写覆盖当天，不会刷屏。
     *
     * <p>briefDate 可显式指定：夜间规则 22:00 写的是「明天」的晨报，
     * 次日 07:30 推送按 brief_date=当天 精确匹配——两边必须落在同一天，否则永远推空。
     */
    private AiAgentToolResult saveDailyBrief(Long userId, JsonNode args) {
        String content = textArg(args, "content");
        if (!StringUtils.hasText(content)) {
            return new AiAgentToolResult("content 不能为空", true);
        }
        LocalDate briefDate = LocalDate.now();
        String rawDate = textArg(args, "briefDate");
        if (StringUtils.hasText(rawDate)) {
            try {
                briefDate = LocalDate.parse(rawDate.strip());
            } catch (DateTimeParseException e) {
                return new AiAgentToolResult("briefDate 格式无效，应为 YYYY-MM-DD", true);
            }
        }
        try {
            long projectId = args.path("projectId").asLong(0);
            Long id = projectBriefService.save(userId, projectId <= 0 ? null : projectId,
                    briefDate, content, textArg(args, "taskId"));
            return new AiAgentToolResult("晨报已保存（id=" + id + "，日期 " + briefDate + "）", false);
        } catch (Exception e) {
            return new AiAgentToolResult("晨报保存失败：" + e.getMessage(), true);
        }
    }

    /**
     * 写制品：夜间产出的方案/补丁落库后立刻自己送审（DRAFT → IN_REVIEW），白天人只做评审。
     * evidence 必须给全 project_fact.id，无证据的制品不允许入队（无证据不派活的下半句）。
     * 自动送审失败不回退工具结果：制品仍是草稿，白天人可手动提交。
     */
    private AiAgentToolResult saveArtifact(Long userId, JsonNode args) {
        long projectId = args.path("projectId").asLong(0);
        if (projectId <= 0) {
            return new AiAgentToolResult("缺少 projectId", true);
        }
        String title = textArg(args, "title");
        String kind = textArg(args, "kind");
        String content = textArg(args, "content");
        String evidence = textArg(args, "evidence");
        if (!StringUtils.hasText(title)) {
            return new AiAgentToolResult("title 不能为空", true);
        }
        if (!StringUtils.hasText(kind)) {
            return new AiAgentToolResult("kind 不能为空（PLAN/PATCH/FIX_NOTE/REVIEW_FIX/CHECKLIST）", true);
        }
        if (!StringUtils.hasText(content)) {
            return new AiAgentToolResult("content 不能为空", true);
        }
        if (!StringUtils.hasText(evidence)) {
            return new AiAgentToolResult(AiArtifactService.EVIDENCE_REQUIRED, true);
        }
        try {
            AiArtifact artifact = aiArtifactService.create(userId, projectId,
                    new AiArtifactService.ArtifactCreateRequest(title, kind, content, evidence,
                            null, null, projectId, textArg(args, "taskId")));
            String status = artifact.getStatus();
            try {
                AiArtifact submitted = aiArtifactService.submit(userId, artifact.getId());
                if (submitted != null && submitted.getStatus() != null) {
                    status = submitted.getStatus();
                }
            } catch (Exception e) {
                log.warn("制品 {} 自动送审失败，保留草稿待人工提交：{}", artifact.getId(), e.getMessage());
            }
            return new AiAgentToolResult("制品已入评审队列（id=" + artifact.getId()
                    + "，kind=" + artifact.getKind() + "，状态 " + status + "）", false);
        } catch (IllegalArgumentException e) {
            return new AiAgentToolResult(e.getMessage(), true);
        } catch (Exception e) {
            return new AiAgentToolResult("制品保存失败：" + e.getMessage(), true);
        }
    }

    private String textArg(JsonNode args, String name) {
        JsonNode node = args.path(name);
        return node.isTextual() ? node.asText() : null;
    }

    private AiAgentToolResult weeklyReportFacts(JsonNode args) {
        try {
            LocalDate weekStart = parseWeekStart(textArg(args, "weekStart"));
            Map<String, Object> facts = weeklyReportService.collectFacts(weekStart);
            return new AiAgentToolResult(objectMapper.writeValueAsString(facts), false);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return new AiAgentToolResult("weekStart 格式无效，应为周一日期 YYYY-MM-DD", true);
        } catch (Exception e) {
            return new AiAgentToolResult("周报事实采集失败：" + e.getMessage(), true);
        }
    }

    private AiAgentToolResult weeklyReportInput(JsonNode args) {
        String wecomSummary = textArg(args, "wecomSummary");
        String manualNotes = textArg(args, "manualNotes");
        if (!StringUtils.hasText(wecomSummary) && !StringUtils.hasText(manualNotes)) {
            return new AiAgentToolResult("请提供 wecomSummary 或 manualNotes 至少一项", true);
        }
        try {
            LocalDate weekStart = parseWeekStart(textArg(args, "weekStart"));
            var report = weeklyReportService.getOrCreate(weekStart);
            weeklyReportService.saveInputs(report.getId(), wecomSummary, manualNotes, null);
            return new AiAgentToolResult("已写入本周（" + weekStart + "）周报草稿："
                    + (StringUtils.hasText(wecomSummary) ? "企微智能总结 " : "")
                    + (StringUtils.hasText(manualNotes) ? "补充信息" : ""), false);
        } catch (Exception e) {
            return new AiAgentToolResult("写入周报失败：" + e.getMessage(), true);
        }
    }

    private LocalDate parseWeekStart(String value) {
        if (!StringUtils.hasText(value)) {
            return LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        }
        LocalDate date = LocalDate.parse(value);
        if (date.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("weekStart 必须是周一");
        }
        return date;
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
