package com.bu.management.service;

import com.bu.management.vo.AiAgentToolDefinition;
import com.bu.management.vo.AiAgentToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 邮件行动工具（AI 助手）：查每日摘要/待办、把待办/风险转成任务或事项、
 * 起草并发送 SMTP 回复。与 ConnectorToolService 的只读邮箱工具互补；
 * 写操作（转化/发信）都要求用户在会话里明确确认，系统提示词已约束。
 * 任何失败都转换为 isError 结果，绝不向侧车抛异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailActionToolService {

    private final EmailDigestService digestService;
    private final EmailActionService actionService;
    private final EmailReplyService replyService;
    private final ObjectMapper objectMapper;

    public static final Set<String> TOOL_NAMES = Set.of(
            "get_my_email_digest", "convert_email_item_to_task", "reply_my_email");

    public boolean handles(String toolName) {
        return TOOL_NAMES.contains(toolName);
    }

    public List<AiAgentToolDefinition> definitions() {
        return List.of(
                new AiAgentToolDefinition("get_my_email_digest",
                        "查询当前用户某天（默认昨天）的邮件 AI 摘要：总览、待办、风险、回复建议",
                        objectSchema(Map.of(
                                "date", stringProperty("日期 YYYY-MM-DD，默认昨天"),
                                "section", stringProperty("可选过滤：overview/todos/risks/replies，缺省返回全部")), null)),
                new AiAgentToolDefinition("convert_email_item_to_task",
                        "把邮件摘要里的某条待办/风险转成系统任务（TASK）或事项（ISSUE）。需要先 get_my_email_digest 拿到 messageId 与标题，并向用户确认后再调用",
                        objectSchema(Map.of(
                                "emailId", integerProperty("来源邮件 ID（必填），来自 get_my_email_digest 的 messageId"),
                                "itemKind", stringProperty("条目类别：TODO/RISK/IMPORTANT（必填）"),
                                "itemTitle", stringProperty("条目标题（必填）"),
                                "target", stringProperty("转化目标：TASK/ISSUE，默认 TASK"),
                                "severity", stringProperty("ISSUE 时的严重程度：高/中/低，默认中")), null)),
                new AiAgentToolDefinition("reply_my_email",
                        "按给定正文内容通过用户绑定邮箱 SMTP 回复某封已同步邮件。正文必须先给用户确认；发送结果即时返回",
                        objectSchema(Map.of(
                                "emailId", integerProperty("要回复的邮件 ID（必填）"),
                                "subject", stringProperty("回复主题，缺省自动加「回复：」前缀"),
                                "bodyText", stringProperty("回复正文（必填，纯文本）")),
                                List.of("emailId", "bodyText"))));
    }

    public AiAgentToolResult execute(Long userId, String toolName, JsonNode args) {
        try {
            return switch (toolName) {
                case "get_my_email_digest" -> getDigest(userId, args);
                case "convert_email_item_to_task" -> convertItem(userId, args);
                case "reply_my_email" -> replyEmail(userId, args);
                default -> new AiAgentToolResult("未知工具：" + toolName, true);
            };
        } catch (Exception e) {
            log.warn("邮件行动工具执行失败: tool={}, error={}", toolName, e.getMessage());
            return new AiAgentToolResult("工具执行失败：" + e.getMessage(), true);
        }
    }

    private AiAgentToolResult getDigest(Long userId, JsonNode args) {
        java.time.LocalDate date = parseDate(textArg(args, "date"));
        var response = digestService.getResponse(userId, date);
        String section = textArg(args, "section");
        StringBuilder sb = new StringBuilder("邮件摘要（").append(response.businessDate())
                .append("，状态 ").append(response.status()).append("）：");
        sb.append("\n总览：").append(response.overview() == null || response.overview().isBlank()
                ? "（无）" : response.overview());
        boolean filtered = !"todos".equals(section) && !"risks".equals(section)
                && !"replies".equals(section);
        if (filtered || "todos".equals(section)) {
            appendItems(sb, "待办", response.todos());
        }
        if (filtered || "risks".equals(section)) {
            appendItems(sb, "风险", response.risks());
        }
        if (filtered || "replies".equals(section)) {
            appendItems(sb, "回复建议", response.replySuggestions());
        }
        return new AiAgentToolResult(sb.toString(), false);
    }

    private void appendItems(StringBuilder sb, String label, JsonNode items) {
        sb.append("\n").append(label).append("（").append(items.size()).append(" 条）:");
        if (items.isEmpty()) {
            sb.append(" 无");
            return;
        }
        int index = 0;
        for (JsonNode item : items) {
            if (index++ >= 10) {
                sb.append("\n…（其余省略）");
                break;
            }
            String title = item.path("title").asText(item.path("subject").asText("（无标题）"));
            sb.append("\n- emailId=").append(item.path("messageId").asLong())
                    .append("，itemKind=").append(labelToKind(label))
                    .append("，itemTitle=").append(title);
            String action = item.path("action").asText(item.path("content").asText(""));
            if (!action.isBlank()) {
                sb.append("，说明=").append(action);
            }
        }
    }

    private String labelToKind(String label) {
        return switch (label) {
            case "风险" -> "RISK";
            case "回复建议" -> "REPLY";
            default -> "TODO";
        };
    }

    private AiAgentToolResult convertItem(Long userId, JsonNode args) {
        Long emailId = longArg(args, "emailId");
        String itemKind = textArg(args, "itemKind");
        String itemTitle = textArg(args, "itemTitle");
        if (emailId == null || itemKind == null || itemTitle == null) {
            return new AiAgentToolResult("缺少 emailId/itemKind/itemTitle，请先 get_my_email_digest 获取条目", true);
        }
        String target = textArg(args, "target");
        String actionType = "ISSUE".equalsIgnoreCase(target) ? "ISSUE" : "TASK";
        var result = "ISSUE".equals(actionType)
                ? actionService.convertToIssue(userId, emailId, itemKind, itemTitle,
                        textArg(args, "severity") == null ? "中" : textArg(args, "severity"), userId)
                : actionService.convertToTask(userId, emailId, itemKind, itemTitle, null, userId);
        return new AiAgentToolResult("已" + (result.created() ? "创建" : "复用已有")
                + (actionType.equals("ISSUE") ? "事项" : "任务")
                + "（ID=" + result.targetId() + "）：完成该" + (actionType.equals("ISSUE") ? "事项" : "任务")
                + "后，邮件摘要中的条目会自动标记为已闭环。", false);
    }

    private AiAgentToolResult replyEmail(Long userId, JsonNode args) {
        Long emailId = longArg(args, "emailId");
        String bodyText = textArg(args, "bodyText");
        if (emailId == null || bodyText == null || bodyText.isBlank()) {
            return new AiAgentToolResult("缺少 emailId 或 bodyText", true);
        }
        var result = replyService.send(userId, emailId, textArg(args, "subject"), bodyText);
        if ("SENT".equals(result.status())) {
            return new AiAgentToolResult("回复已通过绑定邮箱发送成功（记录 #" + result.replyId() + "）。", false);
        }
        return new AiAgentToolResult("回复发送失败：" + result.errorMessage(), true);
    }

    private java.time.LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式无效，请使用 YYYY-MM-DD");
        }
    }

    private String textArg(JsonNode args, String name) {
        JsonNode node = args.path(name);
        return node.isTextual() ? node.asText() : null;
    }

    private Long longArg(JsonNode args, String name) {
        JsonNode node = args.path(name);
        return node.canConvertToLong() ? node.asLong() : null;
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
