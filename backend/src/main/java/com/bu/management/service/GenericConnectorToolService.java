package com.bu.management.service;

import com.bu.management.entity.AiConnector;
import com.bu.management.vo.AiAgentToolDefinition;
import com.bu.management.vo.AiAgentToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 通用连接器工具层：对启用中的 ai_connector 注册项动态生成
 * query_{code}（搜索）与 read_{code}（读取）两个只读工具，
 * 通过 GenericConnectorClient 以注册的凭据调用外部系统。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenericConnectorToolService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final AiConnectorRegistryService registryService;
    private final GenericConnectorClient client;

    /** 生成的工具名前缀。 */
    private static final String QUERY_PREFIX = "query_";
    private static final String READ_PREFIX = "read_";

    /** 启用连接器的动态工具定义。 */
    public List<AiAgentToolDefinition> definitions() {
        List<AiAgentToolDefinition> defs = new ArrayList<>();
        for (AiConnector connector : registryService.listEnabled()) {
            if (!StringUtils.hasText(connector.getQueryPath()) && !StringUtils.hasText(connector.getReadPath())) {
                continue;
            }
            if (StringUtils.hasText(connector.getQueryPath())) {
                Map<String, JsonNode> props = new LinkedHashMap<>();
                props.put("keyword", stringProperty("搜索关键词"));
                props.put("limit", integerProperty("返回条数上限，默认 10，最大 50"));
                defs.add(new AiAgentToolDefinition(QUERY_PREFIX + connector.getCode(),
                        "查询外部系统「" + connector.getName() + "」，按关键词搜索",
                        objectSchema(props)));
            }
            if (StringUtils.hasText(connector.getReadPath())) {
                Map<String, JsonNode> props = new LinkedHashMap<>();
                props.put("id", stringProperty("记录 ID（必填），来自 " + QUERY_PREFIX + connector.getCode() + " 结果"));
                defs.add(new AiAgentToolDefinition(READ_PREFIX + connector.getCode(),
                        "读取外部系统「" + connector.getName() + "」某条记录详情，id 来自搜索结果",
                        objectSchema(props, List.of("id"))));
            }
        }
        return defs;
    }

    /** 工具执行入口；仅接受本服务生成的工具名。 */
    public AiAgentToolResult execute(String toolName, JsonNode args) {
        try {
            for (AiConnector connector : registryService.listEnabled()) {
                if ((QUERY_PREFIX + connector.getCode()).equals(toolName)) {
                    return query(connector, args);
                }
                if ((READ_PREFIX + connector.getCode()).equals(toolName)) {
                    return read(connector, args);
                }
            }
            return new AiAgentToolResult("未知工具：" + toolName, true);
        } catch (Exception e) {
            log.warn("通用连接器工具执行失败: tool={}, error={}", toolName, e.getMessage());
            return new AiAgentToolResult("工具执行失败：" + sanitize(e.getMessage()), true);
        }
    }

    private AiAgentToolResult query(AiConnector connector, JsonNode args) {
        if (!StringUtils.hasText(connector.getQueryPath())) {
            return new AiAgentToolResult("「" + connector.getName() + "」未配置查询接口路径", true);
        }
        String keyword = textArg(args, "keyword");
        int limit = Math.min(Math.max(intArg(args, "limit", DEFAULT_LIMIT), 1), MAX_LIMIT);
        StringBuilder path = new StringBuilder(connector.getQueryPath());
        path.append(path.toString().contains("?") ? '&' : '?');
        path.append("keyword=").append(keyword == null ? "" : java.net.URLEncoder.encode(keyword,
                java.nio.charset.StandardCharsets.UTF_8));
        path.append("&limit=").append(limit);
        JsonNode data = client.getJson(connector, path.toString());
        List<Map<String, Object>> rows = client.toRows(data);
        if (rows.isEmpty()) {
            return new AiAgentToolResult("「" + connector.getName() + "」中未找到与「"
                    + (keyword == null ? "" : keyword) + "」相关的记录", false);
        }
        StringBuilder sb = new StringBuilder("「").append(connector.getName()).append("」查询结果，共 ")
                .append(rows.size()).append(" 条：");
        for (Map<String, Object> row : rows) {
            sb.append("\n- ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getValue() == null) continue;
                if (!first) sb.append("，");
                sb.append(entry.getKey()).append("=").append(truncate(String.valueOf(entry.getValue()), 120));
                first = false;
            }
        }
        return new AiAgentToolResult(sb.toString(), false);
    }

    private AiAgentToolResult read(AiConnector connector, JsonNode args) {
        if (!StringUtils.hasText(connector.getReadPath())) {
            return new AiAgentToolResult("「" + connector.getName() + "」未配置读取接口路径", true);
        }
        String id = textArg(args, "id");
        if (!StringUtils.hasText(id)) {
            return new AiAgentToolResult("缺少 id，请先调用 query_" + connector.getCode() + " 获取记录 ID", true);
        }
        String path = connector.getReadPath().contains("{id}")
                ? connector.getReadPath().replace("{id}", urlEncode(id))
                : connector.getReadPath() + "/" + urlEncode(id);
        JsonNode data = client.getJson(connector, path);
        String body = data == null ? "" : data.toString();
        if (body.length() > 6000) {
            body = body.substring(0, 6000) + "…（内容过长已截断）";
        }
        return new AiAgentToolResult("「" + connector.getName() + "」记录详情：\n" + body, false);
    }

    private String textArg(JsonNode args, String name) {
        JsonNode node = args.path(name);
        return node.isTextual() ? node.asText() : null;
    }

    private int intArg(JsonNode args, String name, int fallback) {
        JsonNode node = args.path(name);
        return node.canConvertToInt() ? node.asInt() : fallback;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    private String sanitize(String message) {
        if (message == null) return "未知错误";
        return message.replaceAll("(?i)(token|password|secret|api[_-]?key)=[^&\\s,;\"']+", "$1=***");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode objectSchema(Map<String, JsonNode> properties) {
        var schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        properties.forEach(props::set);
        return schema;
    }

    private JsonNode objectSchema(Map<String, JsonNode> properties, List<String> required) {
        var schema = objectSchema(properties);
        if (required != null && !required.isEmpty()) {
            var requiredNode = schema.putArray("required");
            required.forEach(requiredNode::add);
        }
        return schema;
    }

    private JsonNode stringProperty(String description) {
        var node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private JsonNode integerProperty(String description) {
        var node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }
}
