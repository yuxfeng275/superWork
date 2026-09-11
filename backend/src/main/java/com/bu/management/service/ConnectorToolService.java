package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailAccount;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.Project;
import com.bu.management.entity.User;
import com.bu.management.entity.YunxiaoProjectMapping;
import com.bu.management.integration.SeeyonOaClient;
import com.bu.management.integration.WorktimeClient;
import com.bu.management.integration.YuqueMcpClient;
import com.bu.management.mapper.EmailAccountMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoProjectMappingMapper;
import com.bu.management.vo.AiAgentToolDefinition;
import com.bu.management.vo.AiAgentToolResult;
import com.bu.management.vo.WorkItemOverviewItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 连接器只读工具：邮箱（本地库）、云效（工作项查询服务）、OA（致远）、
 * 语雀（MCP）、工时系统（HTTP）。仅当连接器启用且已配置时才下发工具定义
 * （邮箱工具恒下发，执行期检查用户是否绑定邮箱）。
 * 任何失败都转换为 isError 结果，绝不向侧车抛异常；输出/错误消息不含凭据。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorToolService {

    private static final String GROUP = "ai-connector";
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAIL_MAX_LIMIT = 50;
    private static final int MAIL_BODY_MAX_CHARS = 8_000;
    private static final int YUNXIAO_MAX_LIMIT = 50;

    private final SystemConfigService configService;
    private final AiConnectorIdentityService identityService;
    private final EmailMessageMapper emailMessageMapper;
    private final EmailAccountMapper emailAccountMapper;
    private final YunxiaoWorkItemQueryService yunxiaoQueryService;
    private final YunxiaoProjectMappingMapper yunxiaoProjectMappingMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final SeeyonOaClient seeyonOaClient;
    private final SeeyonOaConfigService seeyonOaConfigService;
    private final YunxiaoConfigService yunxiaoConfigService;
    private final YuqueMcpClient yuqueMcpClient;
    private final WorktimeClient worktimeClient;
    private final WorktimeAnalyticsService worktimeAnalyticsService;
    private final SysRoleService sysRoleService;
    private final ObjectMapper objectMapper;
    private final WorktimeInsightToolService worktimeInsightToolService;

    /**
     * 连接器工具定义；仅包含已启用且已配置的连接器（邮箱工具恒下发，
     * 执行期检查 EmailAccount 是否存在）。
     */
    public List<AiAgentToolDefinition> definitions() {
        List<AiAgentToolDefinition> defs = new ArrayList<>();

        // 邮箱：恒下发，执行期检查 EmailAccount
        defs.add(new AiAgentToolDefinition("search_my_emails", "搜索当前用户已同步到系统的邮件，可按关键词/发件人/日期范围过滤",
                objectSchema(Map.of(
                        "keyword", stringProperty("关键词，匹配邮件标题或正文预览"),
                        "sender", stringProperty("发件人地址或姓名关键词"),
                        "dateFrom", stringProperty("起始日期 YYYY-MM-DD"),
                        "dateTo", stringProperty("结束日期 YYYY-MM-DD"),
                        "limit", integerProperty("返回条数上限，默认 10，最大 50")), null)));
        defs.add(new AiAgentToolDefinition("read_my_email", "读取当前用户某封已同步邮件的正文内容，emailId 来自 search_my_emails 结果",
                objectSchema(Map.of(
                        "emailId", integerProperty("邮件 ID（必填），来自 search_my_emails 结果")),
                        List.of("emailId"))));

        // 云效
        if (yunxiaoReady()) {
            defs.add(new AiAgentToolDefinition("query_yunxiao_projects", "列出云效已同步的项目，用于定位 projectId",
                    objectSchema(Map.of(
                            "limit", integerProperty("返回条数上限，默认 10，最大 20")), null)));
            defs.add(new AiAgentToolDefinition("query_yunxiao_workitems", "查询云效工作项（Req=需求/Task=任务/Bug=缺陷），可按项目/负责人/关键词过滤",
                    objectSchema(Map.of(
                            "projectId", stringProperty("云效项目 ID，来自 query_yunxiao_projects 结果，缺省查全部可见项目"),
                            "category", stringProperty("工作项类型：Req=需求，Task=任务，Bug=缺陷，缺省查全部类型"),
                            "assigneeMe", booleanProperty("是否只看当前用户负责的，默认 true"),
                            "keyword", stringProperty("标题或编号关键词"),
                            "limit", integerProperty("返回条数上限，默认 10，最大 50")), null)));
            defs.add(new AiAgentToolDefinition("get_yunxiao_workitem", "读取云效工作项详情（含描述/优先级/工时/截止日期），workitemId 来自 query_yunxiao_workitems 结果",
                    objectSchema(Map.of(
                            "workitemId", stringProperty("云效工作项 ID（必填），来自 query_yunxiao_workitems 结果的 workitemId 字段")),
                            List.of("workitemId"))));
        }

        // OA
        if (seeyonReady()) {
            defs.add(new AiAgentToolDefinition("query_oa_pending", "查询当前用户在致远 OA 的待办事项",
                    objectSchema(Map.of(
                            "keyword", stringProperty("标题关键词过滤"),
                            "limit", integerProperty("返回条数上限，默认 10，最大 50")), null)));
            defs.add(new AiAgentToolDefinition("query_oa_done", "查询当前用户在致远 OA 的已办事项",
                    objectSchema(Map.of(
                            "keyword", stringProperty("标题关键词过滤"),
                            "limit", integerProperty("返回条数上限，默认 10，最大 50")), null)));
            defs.add(new AiAgentToolDefinition("get_oa_flow", "查询致远 OA 流程实例详情，flowId 来自待办/已办事项",
                    objectSchema(Map.of(
                            "flowId", stringProperty("流程实例 ID（必填），来自待办/已办事项的 flowId")),
                            List.of("flowId"))));
        }

        // 语雀
        if (yuqueReady()) {
            defs.add(new AiAgentToolDefinition("search_yuque_docs", "在语雀知识库中搜索文档（组织共享知识）",
                    objectSchema(Map.of(
                            "keyword", stringProperty("搜索关键词（必填）"),
                            "limit", integerProperty("返回条数上限，默认 5，最大 10")),
                            List.of("keyword"))));
            defs.add(new AiAgentToolDefinition("read_yuque_doc", "读取语雀文档正文（Markdown），doc 为文档 URL 或知识库/文档 slug",
                    objectSchema(Map.of(
                            "doc", stringProperty("文档 URL 或 slug（必填），来自 search_yuque_docs 结果")),
                            List.of("doc"))));
        }
        // 工时系统：query_my_worktime 恒下发（读本地同步库），分析工具同源
        defs.add(new AiAgentToolDefinition("query_my_worktime", "查询当前用户在工时系统的月度工时汇总与填报状态",
                objectSchema(Map.of(
                        "month", stringProperty("月份 YYYY-MM，默认上个月"),
                        "detail", booleanProperty("是否返回项目明细，默认 true")), null)));
        defs.add(new AiAgentToolDefinition("analyze_my_worktime", "分析当前用户近几个月的工时趋势与项目分布，对比环比变化，判断填报是否偏少",
                objectSchema(Map.of(
                        "months", stringProperty("对比月份数，默认 3（含上个月往前推）"),
                        "month", stringProperty("主分析月份 YYYY-MM，默认上个月")), null)));
        defs.add(new AiAgentToolDefinition("analyze_team_worktime", "按业务线汇总全团队某月工时投入并列出未填报成员（需要营收查看权限）",
                objectSchema(Map.of(
                        "month", stringProperty("月份 YYYY-MM，默认上个月")), null)));

        defs.addAll(worktimeInsightToolService.definitions());
        return defs;
    }

    /** 连接器状态项：code/名称/状态（READY、DISABLED、NOT_CONFIGURED）/提示文案。 */
    public record ConnectorStatus(String code, String name, String status, String hint) {}

    /**
     * 连接器状态列表，供前端「AI 连接器」面板展示；就绪判定复用 definitions()
     * 的裁剪口径，未就绪时进一步区分未启用与未配置。
     */
    public List<ConnectorStatus> statuses() {
        List<ConnectorStatus> list = new ArrayList<>();
        // 邮箱：恒就绪，执行期按用户检查 EmailAccount
        list.add(new ConnectorStatus("mail", "邮箱", "READY",
                "按用户隔离：在「邮箱管理」绑定邮箱后即可让 AI 查询已同步邮件"));
        // 云效
        if (yunxiaoReady()) {
            list.add(new ConnectorStatus("yunxiao", "云效", "READY", "已就绪，AI 可查询工作项"));
        } else {
            try {
                var cfg = yunxiaoConfigService.getRuntimeConfig();
                list.add(cfg.enabled()
                        ? new ConnectorStatus("yunxiao", "云效", "NOT_CONFIGURED", "云效连接参数不完整，请先完成云效配置")
                        : new ConnectorStatus("yunxiao", "云效", "DISABLED", "请在「BU驾驶舱 → 云效配置」启用"));
            } catch (Exception e) {
                list.add(new ConnectorStatus("yunxiao", "云效", "DISABLED", "配置读取失败"));
            }
        }
        // OA（致远）
        if (seeyonReady()) {
            list.add(new ConnectorStatus("oa", "OA（致远）", "READY", "已就绪，AI 可查询待办/已办/流程"));
        } else {
            try {
                var cfg = seeyonOaConfigService.getRuntimeConfig();
                list.add(cfg.enabled()
                        ? new ConnectorStatus("oa", "OA（致远）", "NOT_CONFIGURED", "OA 连接参数不完整，请补全服务地址与账号")
                        : new ConnectorStatus("oa", "OA（致远）", "DISABLED", "请在 OA 集成配置中启用"));
            } catch (Exception e) {
                list.add(new ConnectorStatus("oa", "OA（致远）", "DISABLED", "配置读取失败"));
            }
        }
        // 语雀
        if (yuqueReady()) {
            list.add(new ConnectorStatus("yuque", "语雀", "READY", "已就绪，AI 可搜索/阅读语雀文档"));
        } else {
            try {
                list.add(yuqueMcpClient.enabled()
                        ? new ConnectorStatus("yuque", "语雀", "NOT_CONFIGURED", "请在 配置管理 → AI 连接器 补全 MCP 地址与 Token")
                        : new ConnectorStatus("yuque", "语雀", "DISABLED", "请在 配置管理 → AI 连接器 启用"));
            } catch (Exception e) {
                list.add(new ConnectorStatus("yuque", "语雀", "DISABLED", "配置读取失败"));
            }
        }
        // 工时系统：读本地同步库（revenue_worklog_entry），恒就绪；身份按 ai_connector_identity 或姓名匹配
        list.add(new ConnectorStatus("worktime", "工时系统", "READY",
                "已接入：AI 可查询你的月度工时、趋势分析；团队分析需营收权限"));
        return list;
    }

    /** 内置连接器工具名集合。 */
    private static final Set<String> BUILTIN_TOOLS = Set.of(
            "search_my_emails", "read_my_email",
            "query_yunxiao_projects", "query_yunxiao_workitems", "get_yunxiao_workitem",
            "query_oa_pending", "query_oa_done", "get_oa_flow",
            "search_yuque_docs", "read_yuque_doc", "query_my_worktime",
            "analyze_my_worktime", "analyze_team_worktime",
            "analyze_my_contracts", "analyze_cost_structure", "get_kpi_summary", "get_worktime_sync_status");

    /** 该工具名是否属于内置连接器工具集（含工时经营洞察工具）。 */
    public boolean handles(String toolName) {
        return BUILTIN_TOOLS.contains(toolName) || worktimeInsightToolService.handles(toolName);
    }

    /**
     * 执行连接器工具；userId 来自 AiAgentToolService 的 runId 注册表。
     */
    public AiAgentToolResult execute(Long userId, String toolName, JsonNode args) {
        try {
            return switch (toolName) {
                case "search_my_emails" -> searchMyEmails(userId, args);
                case "read_my_email" -> readMyEmail(userId, args);
                case "query_yunxiao_projects" -> queryYunxiaoProjects(args);
                case "query_yunxiao_workitems" -> queryYunxiaoWorkitems(userId, args);
                case "get_yunxiao_workitem" -> getYunxiaoWorkitem(userId, args);
                case "query_oa_pending" -> queryOaAffairs(userId, args, true);
                case "query_oa_done" -> queryOaAffairs(userId, args, false);
                case "get_oa_flow" -> getOaFlow(args);
                case "search_yuque_docs" -> searchYuqueDocs(args);
                case "read_yuque_doc" -> readYuqueDoc(args);
                case "query_my_worktime" -> queryMyWorktime(userId, args);
                case "analyze_my_worktime" -> analyzeMyWorktime(userId, args);
                case "analyze_team_worktime" -> analyzeTeamWorktime(userId, args);
                default -> worktimeInsightToolService.handles(toolName)
                        ? worktimeInsightToolService.execute(userId, toolName, args)
                        : new AiAgentToolResult("未知工具：" + toolName, true);
            };
        } catch (Exception e) {
            log.warn("AI 连接器工具执行失败: tool={}, error={}", toolName, e.getMessage());
            return new AiAgentToolResult("工具执行失败：" + sanitize(e.getMessage()), true);
        }
    }

    // ==================== 邮箱（本地库） ====================

    private AiAgentToolResult searchMyEmails(Long userId, JsonNode args) {
        if (!hasEmailAccount(userId)) {
            return new AiAgentToolResult("当前用户尚未绑定邮箱，请先在邮箱管理中配置", true);
        }
        LocalDate dateFrom = parseDate(textArg(args, "dateFrom"));
        LocalDate dateTo = parseDate(textArg(args, "dateTo"));
        if (textArg(args, "dateFrom") != null && dateFrom == null) {
            return new AiAgentToolResult("日期格式无效，请使用 YYYY-MM-DD", true);
        }
        if (textArg(args, "dateTo") != null && dateTo == null) {
            return new AiAgentToolResult("日期格式无效，请使用 YYYY-MM-DD", true);
        }
        LambdaQueryWrapper<EmailMessage> query = new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getOwnerUserId, userId)
                .orderByDesc(EmailMessage::getReceivedAt)
                .orderByDesc(EmailMessage::getId);
        if (dateFrom != null) {
            query.ge(EmailMessage::getReceivedAt, dateFrom.atStartOfDay());
        } else {
            query.ge(EmailMessage::getReceivedAt,
                    LocalDate.now().minusDays(mailSearchDays()).atStartOfDay());
        }
        if (dateTo != null) {
            query.le(EmailMessage::getReceivedAt, dateTo.atTime(23, 59, 59));
        }
        String keyword = textArg(args, "keyword");
        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(EmailMessage::getSubject, keyword)
                    .or().like(EmailMessage::getBodyPreview, keyword));
        }
        String sender = textArg(args, "sender");
        if (StringUtils.hasText(sender)) {
            query.and(q -> q.like(EmailMessage::getSenderAddress, sender)
                    .or().like(EmailMessage::getSenderName, sender));
        }
        List<EmailMessage> messages =
                emailMessageMapper.selectList(query.last("LIMIT " + mailLimit(args)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EmailMessage message : messages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("emailId", message.getId());
            row.put("subject", message.getSubject());
            row.put("sender", StringUtils.hasText(message.getSenderName())
                    ? message.getSenderName() : message.getSenderAddress());
            row.put("receivedAt", message.getReceivedAt());
            row.put("preview", message.getBodyPreview());
            rows.add(row);
        }
        return new AiAgentToolResult(render(userId, "已同步邮件", rows), false);
    }

    private AiAgentToolResult readMyEmail(Long userId, JsonNode args) {
        if (!hasEmailAccount(userId)) {
            return new AiAgentToolResult("当前用户尚未绑定邮箱，请先在邮箱管理中配置", true);
        }
        JsonNode idNode = args.path("emailId");
        if (!idNode.canConvertToInt()) {
            return new AiAgentToolResult("缺少 emailId，请先调用 search_my_emails 获取邮件 ID", true);
        }
        EmailMessage message = emailMessageMapper.selectOne(
                new LambdaQueryWrapper<EmailMessage>()
                        .eq(EmailMessage::getId, idNode.asInt())
                        .eq(EmailMessage::getOwnerUserId, userId));
        if (message == null) {
            return new AiAgentToolResult("邮件不存在或不属于当前用户", true);
        }
        String body = StringUtils.hasText(message.getBodyText())
                ? message.getBodyText() : message.getBodyPreview();
        if (!StringUtils.hasText(body)) {
            body = "（无正文内容）";
        }
        if (body.length() > MAIL_BODY_MAX_CHARS) {
            body = body.substring(0, MAIL_BODY_MAX_CHARS) + "…（正文过长已截断）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("邮件「").append(message.getSubject() == null ? "(无主题)" : message.getSubject()).append("」");
        sb.append("，发件人：").append(message.getSenderAddress() == null ? "未知" : message.getSenderAddress());
        sb.append("，时间：").append(message.getReceivedAt() == null ? "未知" : message.getReceivedAt());
        sb.append("\n\n").append(body);
        return new AiAgentToolResult(sb.toString(), false);
    }

    private boolean hasEmailAccount(Long userId) {
        return emailAccountMapper.selectCount(new LambdaQueryWrapper<EmailAccount>()
                .eq(EmailAccount::getOwnerUserId, userId)) > 0;
    }

    // ==================== 云效（查询服务，自带数据权限） ====================

    private AiAgentToolResult queryYunxiaoProjects(JsonNode args) {
        List<YunxiaoProjectMapping> mappings = yunxiaoProjectMappingMapper.selectList(
                new LambdaQueryWrapper<YunxiaoProjectMapping>()
                        .eq(YunxiaoProjectMapping::getSyncEnabled, 1));
        int limit = Math.min(Math.max(intArg(args, "limit", DEFAULT_LIMIT), 1), 20);
        Set<Long> localIds = new LinkedHashSet<>();
        mappings.forEach(mapping -> localIds.add(mapping.getProjectId()));
        Map<Long, Project> projects = localIds.isEmpty()
                ? Map.of()
                : toMapById(projectMapper.selectBatchIds(localIds), Project::getId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (YunxiaoProjectMapping mapping : mappings) {
            if (rows.size() >= limit) break;
            Project project = projects.get(mapping.getProjectId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectId", mapping.getYunxiaoProjectId());
            row.put("projectName", project == null ? "（未关联本地项目）" : project.getName());
            row.put("category", mapping.getCategory());
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return new AiAgentToolResult("云效暂无已同步的项目映射", false);
        }
        return new AiAgentToolResult(render(null, "云效项目", rows), false);
    }

    private AiAgentToolResult queryYunxiaoWorkitems(Long userId, JsonNode args) {
        String role = userRole(userId);
        String category = textArg(args, "category");
        String keyword = textArg(args, "keyword");
        Long localProjectId = null;
        String projectId = textArg(args, "projectId");
        if (StringUtils.hasText(projectId)) {
            // 工具入参为云效项目 ID，翻译为本地项目 ID 供查询服务过滤
            YunxiaoProjectMapping mapping = yunxiaoProjectMappingMapper.selectOne(
                    new LambdaQueryWrapper<YunxiaoProjectMapping>()
                            .eq(YunxiaoProjectMapping::getYunxiaoProjectId, projectId)
                            .last("LIMIT 1"));
            if (mapping == null) {
                return new AiAgentToolResult("未找到该云效项目的同步映射，请先用 query_yunxiao_projects 确认", true);
            }
            localProjectId = mapping.getProjectId();
        }
        boolean assigneeMe = !args.has("assigneeMe") || args.path("assigneeMe").asBoolean(true);
        Long assigneeId = assigneeMe ? userId : null;
        int limit = Math.min(Math.max(intArg(args, "limit", DEFAULT_LIMIT), 1), YUNXIAO_MAX_LIMIT);

        List<String> categories = StringUtils.hasText(category)
                ? List.of(category) : List.of("Req", "Task", "Bug");
        List<WorkItemOverviewItem> items = new ArrayList<>();
        for (String cat : categories) {
            List<WorkItemOverviewItem> part = yunxiaoQueryService.listCloudItems(
                    cat, userId, role, localProjectId, assigneeId, null, keyword);
            for (WorkItemOverviewItem item : part) {
                if (items.size() >= limit) break;
                items.add(item);
            }
            if (items.size() >= limit) break;
        }
        if (items.isEmpty()) {
            return new AiAgentToolResult("当前用户（ID=" + userId + "）暂无符合条件的云效工作项", false);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkItemOverviewItem item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workitemId", item.getYunxiaoWorkitemId());
            row.put("serialNumber", item.getSerialNumber());
            row.put("title", item.getTitle());
            row.put("category", item.getCategory());
            row.put("status", item.getStatus());
            row.put("assignee", item.getAssigneeName());
            row.put("project", item.getProjectName());
            row.put("updatedAt", item.getUpdatedAt());
            if (StringUtils.hasText(item.getDescription())) {
                row.put("description", truncate(item.getDescription(), 150));
            }
            rows.add(row);
        }
        return new AiAgentToolResult(render(userId, "云效工作项（可用 workitemId 调 get_yunxiao_workitem 看详情）", rows), false);
    }

    /**
     * 云效工作项详情：按 workitemId（云效工作项 ID）从缓存读取并展开完整字段。
     */
    private AiAgentToolResult getYunxiaoWorkitem(Long userId, JsonNode args) {
        String workitemId = textArg(args, "workitemId");
        if (!StringUtils.hasText(workitemId)) {
            return new AiAgentToolResult("缺少 workitemId，请先调用 query_yunxiao_workitems 获取", true);
        }
        String role = userRole(userId);
        for (String cat : List.of("Req", "Task", "Bug")) {
            for (WorkItemOverviewItem item : yunxiaoQueryService.listCloudItems(
                    cat, userId, role, null, null, null, workitemId)) {
                if (workitemId.equals(item.getYunxiaoWorkitemId())
                        || workitemId.equals(item.getSerialNumber())) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("serialNumber", item.getSerialNumber());
                    row.put("title", item.getTitle());
                    row.put("category", item.getCategory());
                    row.put("status", item.getStatus());
                    row.put("assignee", item.getAssigneeName());
                    row.put("project", item.getProjectName());
                    row.put("priority", item.getPriority());
                    row.put("dueDate", item.getDueDate());
                    row.put("estimatedHours", item.getEstimatedHours());
                    row.put("actualHours", item.getActualHours());
                    if (StringUtils.hasText(item.getDescription())) {
                        row.put("description", truncate(item.getDescription(), 1500));
                    }
                    row.put("updatedAt", item.getUpdatedAt());
                    return new AiAgentToolResult(render(userId, "云效工作项详情", List.of(row)), false);
                }
            }
        }
        return new AiAgentToolResult("未找到工作项 " + workitemId + "，或其不在你的可见范围内", true);
    }


    // ==================== OA（致远） ====================

    private AiAgentToolResult queryOaAffairs(Long userId, JsonNode args, boolean pending) {
        String memberId = identityService.resolve(userId, AiConnectorIdentityService.CONNECTOR_OA);
        if (memberId == null) {
            return new AiAgentToolResult("未能识别你在 OA 的身份，请联系管理员补录身份映射", true);
        }
        List<JsonNode> affairs = pending
                ? seeyonOaClient.listPendingAffairs() : seeyonOaClient.listDoneAffairs();
        String keyword = textArg(args, "keyword");
        int limit = mailLimit(args);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode affair : affairs) {
            if (rows.size() >= limit) break;
            // 仅返回当前用户相关的事项；字段名以 OA 实际返回为准，多重候选兜底
            String owner = firstText(affair, "memberId", "hmemberId", "senderId", "principalId");
            if (StringUtils.hasText(owner) && !owner.equals(memberId)) {
                continue;
            }
            String subject = affair.path("subject").asText(affair.path("title").asText(""));
            if (StringUtils.hasText(keyword) && !subject.contains(keyword)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subject", subject);
            row.put("appName", affair.path("appName").asText(""));
            row.put("createDate", affair.path("createDate").asText(""));
            row.put("sender", affair.path("senderName").asText(""));
            row.put("flowId", affair.path("flowId").asText(""));
            rows.add(row);
        }
        return new AiAgentToolResult(render(userId, pending ? "OA 待办" : "OA 已办", rows), false);
    }

    private AiAgentToolResult getOaFlow(JsonNode args) {
        String flowId = textArg(args, "flowId");
        if (!StringUtils.hasText(flowId)) {
            return new AiAgentToolResult("缺少 flowId，请先从待办/已办事项中获取", true);
        }
        JsonNode flow = seeyonOaClient.getFlowDetail(flowId);
        StringBuilder sb = new StringBuilder("OA 流程 ").append(flowId).append(" 详情：");
        sb.append("\n- 标题=").append(flow.path("subject").asText(
                flow.path("title").asText("未命名")));
        sb.append("\n- 状态=").append(flow.path("state").asText(
                flow.path("status").asText("未知")));
        sb.append("\n- 发起人=").append(flow.path("senderName").asText(
                flow.path("starter").asText("未知")));
        appendFlowForm(sb, flow);
        return new AiAgentToolResult(sb.toString(), false);
    }

    /**
     * 表单数据展开：formData 为对象时逐字段输出（字段名=值，值截断 120 字符），
     * 含 formTable 数组时按行展开；为纯文本时退回摘要模式（截断 500 字符）。
     */
    private void appendFlowForm(StringBuilder sb, JsonNode flow) {
        JsonNode formData = flow.path("formData");
        if (formData.isObject() && formData.size() > 0) {
            sb.append("\n表单字段：");
            formData.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                String text = value.isValueNode() ? value.asText()
                        : value.isContainerNode() ? value.toString() : String.valueOf(value);
                sb.append("\n- ").append(entry.getKey()).append("=").append(truncate(text, 120));
            });
            return;
        }
        // formTable：致远明细表结构 [{rows:[{label,value}...]}, ...]
        JsonNode formTable = flow.path("formTable");
        if (formTable.isArray() && formTable.size() > 0) {
            sb.append("\n明细表：");
            int line = 0;
            for (JsonNode table : formTable) {
                for (JsonNode row : table.path("rows")) {
                    if (line++ >= 20) {
                        sb.append("\n…（明细行过多已截断）");
                        return;
                    }
                    sb.append("\n- ");
                    boolean first = true;
                    for (JsonNode cell : row.path("cells")) {
                        String label = cell.path("label").asText("");
                        String value = cell.path("value").asText("");
                        if (!first) sb.append("，");
                        sb.append(label).append("=").append(truncate(value, 60));
                        first = false;
                    }
                }
            }
            return;
        }
        String summary = formData.isTextual() ? formData.asText("")
                : flow.path("summary").asText("");
        if (StringUtils.hasText(summary)) {
            sb.append("\n- 摘要=").append(truncate(summary, 500));
        }
    }

    // ==================== 语雀（MCP） ====================

    private AiAgentToolResult searchYuqueDocs(JsonNode args) {
        String keyword = textArg(args, "keyword");
        if (!StringUtils.hasText(keyword)) {
            return new AiAgentToolResult("缺少搜索关键词", true);
        }
        int limit = Math.min(Math.max(intArg(args, "limit", 5), 1), 10);
        List<Map<String, String>> docs = yuqueMcpClient.searchDocs(keyword, limit);
        if (docs.isEmpty()) {
            return new AiAgentToolResult("语雀中未找到与「" + keyword + "」相关的文档", false);
        }
        StringBuilder sb = new StringBuilder("语雀搜索「").append(keyword).append("」，共 ")
                .append(docs.size()).append(" 条：");
        for (Map<String, String> doc : docs) {
            sb.append("\n- 标题=").append(doc.get("title"));
            if (StringUtils.hasText(doc.get("url"))) sb.append("，URL=").append(doc.get("url"));
            if (StringUtils.hasText(doc.get("summary"))) sb.append("，摘要=").append(doc.get("summary"));
        }
        return new AiAgentToolResult(sb.toString(), false);
    }

    private AiAgentToolResult readYuqueDoc(JsonNode args) {
        String doc = textArg(args, "doc");
        if (!StringUtils.hasText(doc)) {
            return new AiAgentToolResult("缺少 doc 参数（文档 URL 或 slug）", true);
        }
        String body = yuqueMcpClient.readDoc(doc);
        return new AiAgentToolResult("语雀文档内容：\n\n" + body, false);
    }

    // ==================== 工时系统 ====================
    private AiAgentToolResult queryMyWorktime(Long userId, JsonNode args) {
        String month = normalizeReportMonth(textArg(args, "month"));
        var report = worktimeAnalyticsService.personalMonthly(userId, month);
        if (report == null) {
            return new AiAgentToolResult(
                    "未能识别你在工时系统的身份：请确认系统内姓名与工时系统一致，或联系管理员在 AI 连接器身份映射中补录", true);
        }
        if (report.entryCount() == 0) {
            return new AiAgentToolResult(report.employeeName() + " " + month
                    + " 月在工时系统无填报记录（或该月尚未同步）。已同步月份以「BU驾驶舱 → 营收管理」导入为准。", false);
        }
        StringBuilder sb = new StringBuilder("工时系统 ").append(month).append(" 月度汇总：状态=已同步，合计=")
                .append(report.totalHours()).append(" 小时，条目数=").append(report.entryCount());
        boolean detail = !args.has("detail") || args.path("detail").asBoolean(true);
        if (detail) {
            appendTopProjects(sb, report.projectHours());
        }
        return new AiAgentToolResult(sb.toString(), false);
    }

    /** 近 N 月趋势 + 主月项目分布 + 环比。 */
    private AiAgentToolResult analyzeMyWorktime(Long userId, JsonNode args) {
        int months = Math.min(Math.max(intArg(args, "months", 3), 2), 6);
        String focusMonth = normalizeReportMonth(textArg(args, "month"));
        var trend = worktimeAnalyticsService.personalTrend(userId, months);
        if (trend == null || trend.isEmpty()) {
            return new AiAgentToolResult("未能识别你在工时系统的身份，或暂无已同步工时数据", true);
        }
        StringBuilder sb = new StringBuilder("近 ").append(trend.size()).append(" 个月工时趋势（")
                .append(trend.get(0).month()).append(" ~ ").append(trend.get(trend.size() - 1).month()).append("）：");
        for (var point : trend) {
            sb.append("\n- ").append(point.month()).append("：").append(point.hours()).append(" 小时（")
                    .append(point.entries()).append(" 条）");
        }
        if (trend.size() >= 2) {
            var last = trend.get(trend.size() - 1);
            var prev = trend.get(trend.size() - 2);
            sb.append("\n环比：").append(prev.hours().signum() == 0 ? "上月无数据"
                    : (last.hours().subtract(prev.hours()).compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                    + last.hours().subtract(prev.hours()).setScale(2, RoundingMode.HALF_UP) + " 小时");
        }
        var report = worktimeAnalyticsService.personalMonthly(userId, focusMonth);
        if (report != null && report.entryCount() > 0) {
            sb.append("\n\n").append(focusMonth).append(" 项目分布（Top 5）：");
            appendTopProjects(sb, report.projectHours());
            String topType = report.workTypeHours().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("");
            if (!topType.isEmpty()) {
                sb.append("\n主要投入类型：").append(topType);
            }
        }
        return new AiAgentToolResult(sb.toString(), false);
    }

    /** 团队月度汇总（revenue:view 权限）。 */
    private AiAgentToolResult analyzeTeamWorktime(Long userId, JsonNode args) {
        List<String> permissions = sysRoleService.getPermissionCodesByUserId(userId);
        if (permissions == null || !permissions.contains("revenue:view")) {
            return new AiAgentToolResult("团队工时分析需要「营收查看」权限，当前账号无权访问", true);
        }
        String month = normalizeReportMonth(textArg(args, "month"));
        var report = worktimeAnalyticsService.teamMonthly(month);
        if (report.entryCount() == 0) {
            return new AiAgentToolResult(month + " 月暂无已同步的团队工时数据", false);
        }
        StringBuilder sb = new StringBuilder("团队 ").append(month).append(" 工时汇总（合计 ")
                .append(report.totalHours()).append(" 小时，").append(report.entryCount()).append(" 条）：");
        for (var line : report.lines()) {
            sb.append("\n- ").append(line.businessLine()).append("：")
                    .append(line.hours()).append(" 小时，").append(line.people()).append(" 人");
        }
        List<String> missing = worktimeAnalyticsService.missingReportUsers(month);
        if (!missing.isEmpty()) {
            sb.append("\n\n本月未填报成员（").append(missing.size()).append("）：").append(String.join("、", missing));
        }
        return new AiAgentToolResult(sb.toString(), false);
    }

    private void appendTopProjects(StringBuilder sb, Map<String, BigDecimal> projectHours) {
        int shown = 0;
        for (Map.Entry<String, BigDecimal> entry : projectHours.entrySet()) {
            if (shown++ >= 5) {
                sb.append("\n…（其余 ").append(projectHours.size() - 5).append(" 个项目省略）");
                break;
            }
            sb.append("\n- ").append(entry.getKey()).append("：").append(entry.getValue()).append(" 小时");
        }
    }

    /** 分析月份默认上个月（同步数据按月滞后），格式非法抛出统一文案。 */
    private String normalizeReportMonth(String month) {
        if (!StringUtils.hasText(month)) {
            return java.time.YearMonth.now().minusMonths(1).toString();
        }
        try {
            return java.time.YearMonth.parse(month).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("月份格式无效，请使用 YYYY-MM");
        }
    }

    // ==================== 就绪判定（动态裁剪） ====================

    private boolean yunxiaoReady() {
        try {
            return yunxiaoConfigService.getRuntimeConfig().isConfigured();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean seeyonReady() {
        try {
            return seeyonOaConfigService.getRuntimeConfig().isConfigured();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean yuqueReady() {
        try {
            return yuqueMcpClient.enabled() && yuqueMcpClient.configured();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean worktimeReady() {
        try {
            return worktimeClient.enabled() && worktimeClient.configured();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 通用辅助 ====================

    private String userRole(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null ? null : user.getRole();
    }

    private <T> Map<Long, T> toMapById(List<T> values, java.util.function.Function<T, Long> idGetter) {
        Map<Long, T> map = new LinkedHashMap<>();
        if (values == null) return map;
        for (T value : values) {
            map.put(idGetter.apply(value), value);
        }
        return map;
    }

    private String render(Long userId, String label, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return (userId == null ? "" : "当前用户（ID=" + userId + "）")
                    + "暂无符合条件的" + label + "记录";
        }
        StringBuilder sb = new StringBuilder();
        if (userId != null) {
            sb.append("当前用户（ID=").append(userId).append("）的");
        }
        sb.append(label).append("，共 ").append(rows.size()).append(" 条：");
        for (Map<String, Object> row : rows) {
            sb.append("\n- ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                if (value == null) continue;
                if (!first) sb.append("，");
                sb.append(entry.getKey()).append("=").append(value);
                first = false;
            }
        }
        return sb.toString();
    }

    private String textArg(JsonNode args, String name) {
        JsonNode node = args.path(name);
        return node.isTextual() ? node.asText() : null;
    }

    private int intArg(JsonNode args, String name, int fallback) {
        JsonNode node = args.path(name);
        return node.canConvertToInt() ? node.asInt() : fallback;
    }

    private int mailLimit(JsonNode args) {
        return Math.min(Math.max(intArg(args, "limit", DEFAULT_LIMIT), 1), MAIL_MAX_LIMIT);
    }

    private int mailSearchDays() {
        try {
            return Math.min(Math.max(
                    Integer.parseInt(configService.getValue(GROUP, "mail.search-days", "90")), 1), 365);
        } catch (NumberFormatException e) {
            return 90;
        }
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    /**
     * 错误消息脱敏：防止凭据片段进入 LLM 上下文。
     */
    private String sanitize(String message) {
        if (message == null) return "未知错误";
        String cleaned = message.replaceAll(
                "(?i)(token|password|secret|api[_-]?key)=[^&\\s,;\"']+", "$1=***");
        return cleaned.length() > 300 ? cleaned.substring(0, 300) + "…" : cleaned;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }

    // ==================== JSON Schema 构建 ====================

    private JsonNode objectSchema(Map<String, JsonNode> properties, List<String> required) {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        properties.forEach(props::set);
        if (required != null && !required.isEmpty()) {
            var requiredNode = schema.putArray("required");
            required.forEach(requiredNode::add);
        }
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

    private JsonNode booleanProperty(String description) {
        var node = objectMapper.createObjectNode();
        node.put("type", "boolean");
        node.put("description", description);
        return node;
    }
}
