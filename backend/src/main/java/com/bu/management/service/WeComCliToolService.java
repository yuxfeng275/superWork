package com.bu.management.service;

import com.bu.management.vo.AiAgentToolDefinition;
import com.bu.management.vo.AiAgentToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 助手的企微机器人通道工具（wecom-cli）：待办、日程、通讯录、文档、消息、邮件。
 *
 * <p>输出约定（沿用官方 Agent Skills 约束）：给用户的可读信息用姓名/主题/时间，**不暴露 userid/chat_id/docid 等内部标识**；
 * 标识仅在工具返回里用于后续调用（如完成待办、读取文档）。
 * 品类未授权时，把企微的续期引导文案（含链接）原样返回给模型，指引用户去连接器页完成授权。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeComCliToolService {

    private static final Set<String> TOOLS = Set.of(
            "wecom_search_contact",
            "wecom_list_todos", "wecom_create_todo", "wecom_finish_todo",
            "wecom_list_schedules",
            "wecom_search_docs", "wecom_read_doc",
            "wecom_send_message",
            "wecom_search_mail");

    private final WeComCliService service;
    private final ObjectMapper objectMapper;

    public boolean handles(String toolName) {
        return TOOLS.contains(toolName);
    }

    public List<AiAgentToolDefinition> definitions() {
        List<AiAgentToolDefinition> defs = new ArrayList<>();
        defs.add(new AiAgentToolDefinition("wecom_search_contact",
                "在企业微信通讯录中按姓名/拼音/别名搜索成员，返回姓名、部门、职务、邮箱。用于把姓名解析成企业微信联系人。",
                objectSchema(Map.of("keyword", stringProperty("姓名或拼音关键词，必填")), List.of("keyword"))));
        defs.add(new AiAgentToolDefinition("wecom_list_todos",
                "查询企业微信待办列表（机器人视角：机器人为创建人、与机器人对话的人为参与人）。返回标题、描述、参与人、截止时间、状态。",
                objectSchema(Map.of(
                        "limit", integerProperty("返回条数，默认 10，最大 20"),
                        "status", stringProperty("状态过滤，可选 proceed / finished，默认 proceed")), List.of())));
        defs.add(new AiAgentToolDefinition("wecom_create_todo",
                "创建企业微信待办并指派参与人。需要参与人时先调用 wecom_search_contact 拿到成员。",
                objectSchema(Map.of(
                        "title", stringProperty("待办标题，必填"),
                        "description", stringProperty("待办描述，可选"),
                        "deadline", stringProperty("截止时间，格式 yyyy-MM-dd HH:mm:ss，可选"),
                        "followerIds", stringProperty("参与人 userid 列表，逗号分隔，可选")), List.of("title"))));
        defs.add(new AiAgentToolDefinition("wecom_finish_todo",
                "把企业微信待办标记为已完成（批量）。",
                objectSchema(Map.of("todoIds", stringProperty("待办 ID 列表，逗号分隔，必填")), List.of("todoIds"))));
        defs.add(new AiAgentToolDefinition("wecom_list_schedules",
                "查询企业微信日程（默认近 7 天到未来 7 天；企微限制只能查当天前后 30 天内）。返回主题、起止时间、地点、创建人、日历。",
                objectSchema(Map.of(
                        "beginTime", stringProperty("开始时间 yyyy-MM-dd HH:mm:ss，可选"),
                        "endTime", stringProperty("结束时间 yyyy-MM-dd HH:mm:ss，可选"),
                        "limit", integerProperty("返回条数，默认 10")), List.of())));
        defs.add(new AiAgentToolDefinition("wecom_search_docs",
                "在企业微信文档中按关键词搜索（在线文档/表格/智能表格等），返回文档名、类型、创建人、链接。",
                objectSchema(Map.of(
                        "keyword", stringProperty("关键词，必填"),
                        "limit", integerProperty("返回条数，默认 10")), List.of("keyword"))));
        defs.add(new AiAgentToolDefinition("wecom_read_doc",
                "读取企业微信文档正文（markdown）。",
                objectSchema(Map.of("docId", stringProperty("文档 ID，必填（来自 wecom_search_docs）")), List.of("docId"))));
        defs.add(new AiAgentToolDefinition("wecom_send_message",
                "用企业微信机器人向指定单聊/群聊发送文本消息。chatId 为成员 userid（单聊）或群会话 ID。",
                objectSchema(Map.of(
                        "chatId", stringProperty("会话 ID：单聊传成员 userid，群聊传群会话 ID，必填"),
                        "text", stringProperty("消息文本，必填")), List.of("chatId", "text"))));
        defs.add(new AiAgentToolDefinition("wecom_search_mail",
                "检索企业微信邮箱中的邮件（只读），返回主题、发件人、时间、已读状态。",
                objectSchema(Map.of(
                        "keyword", stringProperty("搜索关键词，可选"),
                        "limit", integerProperty("返回条数，默认 10")), List.of())));
        return defs;
    }

    public AiAgentToolResult execute(Long userId, String toolName, JsonNode args) {
        try {
            return switch (toolName) {
                case "wecom_search_contact" -> renderList("通讯录成员",
                        service.searchContacts(text(args, "keyword")), "name", "departments", "position", "email");
                case "wecom_list_todos" -> renderList("待办",
                        service.listTodos(intArg(args, "limit", 10), statusFilter(args)), "title", "followers", "deadline", "status", "description");
                case "wecom_create_todo" -> new AiAgentToolResult(
                        "已创建待办：" + service.createTodo(text(args, "title"), text(args, "description"),
                                text(args, "deadline"), splitIds(text(args, "followerIds"))).toString(), false);
                case "wecom_finish_todo" -> new AiAgentToolResult(
                        "已提交完成：" + service.finishTodos(splitIds(text(args, "todoIds"))).toString(), false);
                case "wecom_list_schedules" -> renderList("日程",
                        service.listSchedules(text(args, "beginTime"), text(args, "endTime"), intArg(args, "limit", 10)),
                        "subject", "beginTime", "endTime", "location", "creator", "calendarName");
                case "wecom_search_docs" -> renderList("文档",
                        service.searchDocs(text(args, "keyword"), intArg(args, "limit", 10)), "name", "type", "creator", "modifyTime", "url");
                case "wecom_read_doc" -> new AiAgentToolResult(service.readDoc(text(args, "docId")), false);
                case "wecom_send_message" -> new AiAgentToolResult(
                        "消息已发送：" + service.sendMessage(text(args, "chatId"), text(args, "text")).toString(), false);
                case "wecom_search_mail" -> renderList("邮件",
                        service.searchMail(text(args, "keyword"), intArg(args, "limit", 10)), "subject", "sender", "sendTime", "isRead");
                default -> new AiAgentToolResult("未知工具：" + toolName, true);
            };
        } catch (Exception e) {
            log.warn("企微机器人通道工具失败: tool={}, error={}", toolName, e.getMessage());
            return new AiAgentToolResult(e.getMessage() == null ? "工具执行失败" : e.getMessage(), true);
        }
    }

    // ==================== 输出整形 ====================

    private AiAgentToolResult renderList(String label, List<Map<String, Object>> rows, String... fields) {
        if (rows.isEmpty()) {
            return new AiAgentToolResult(label + "：无数据（可能是品类未授权，可查看连接器页的授权体检）", false);
        }
        StringBuilder builder = new StringBuilder(label).append("共 ").append(rows.size()).append(" 条：\n");
        int index = 1;
        for (Map<String, Object> row : rows) {
            builder.append(index++).append(". ");
            List<String> parts = new ArrayList<>();
            for (String field : fields) {
                Object value = row.get(field);
                if (value == null) continue;
                String rendered = value instanceof List<?> list ? String.join("、", list.stream().map(String::valueOf).toList())
                        : String.valueOf(value);
                if (StringUtils.hasText(rendered)) parts.add(field + "=" + rendered);
            }
            builder.append(String.join(" | ", parts)).append("\n");
        }
        return new AiAgentToolResult(builder.toString().trim(), false);
    }

    // ==================== 参数 ====================

    private String text(JsonNode args, String field) {
        JsonNode node = args.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private int intArg(JsonNode args, String field, int fallback) {
        JsonNode node = args.path(field);
        return node.isNumber() ? node.asInt() : fallback;
    }

    private List<String> splitIds(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        return List.of(value.split("[,\\s]+")).stream().filter(StringUtils::hasText).toList();
    }

    private List<String> statusFilter(JsonNode args) {
        String status = text(args, "status");
        return StringUtils.hasText(status) ? List.of(status) : List.of();
    }

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
}
