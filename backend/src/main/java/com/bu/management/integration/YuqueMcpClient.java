package com.bu.management.integration;

import com.bu.management.service.SystemConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语雀 MCP 客户端：最小 JSON-RPC 2.0 实现（initialize / tools/list / tools/call）。
 * 优先 streamable HTTP（POST mcp-url），失败回退 SSE 端点（mcp-url 同路径 /sse）。
 * token 为组织级凭据，来自系统配置 ai-connector 组；任何错误消息都不回显 token。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YuqueMcpClient {

    private static final String GROUP = "ai-connector";
    private static final int MAX_TEXT_CHARS = 12_000;

    private final SystemConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String cachedToolsUrl;
    private volatile long toolsUrlExpireAt;
    /** MCP 网关 403 后自动降级为语雀 REST v2（同一 Token，X-Auth-Token 头）。 */
    private volatile boolean restFallback;

    private static final String YUQUE_API_BASE = "https://www.yuque.com/api/v2";

    /** MCP 工具调用结果：结构化内容序列化文本 + 原始 isError 标记 */
    public record McpToolResult(String text, boolean isError) {}

    public boolean enabled() {
        return configService.getBoolean(GROUP, "yuque.enabled", false);
    }

    public boolean configured() {
        return StringUtils.hasText(token()) && StringUtils.hasText(mcpUrl());
    }

    /**
     * 连接测试：MCP tools/list 通过即视为可用；MCP 网关 403 时降级 REST v2
     * 并以 /user 探活（同一 Token）。两者都失败才抛错。
     */
    public void testConnection() {
        try {
            listToolNames();
            restFallback = false;
        } catch (IllegalStateException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("403") || message.contains("认证")) {
                restUserProbe();
                restFallback = true;
            } else {
                throw e;
            }
        }
    }

    /**
     * 在语雀中搜索文档（search 工具，工具名运行期发现；MCP 不可用时降级 REST v2）。
     */
    public List<Map<String, String>> searchDocs(String keyword, int limit) {
        if (restFallback) {
            return restSearchDocs(keyword, limit);
        }
        try {
            return mcpSearchDocs(keyword, limit);
        } catch (IllegalStateException e) {
            if (String.valueOf(e.getMessage()).contains("403")) {
                restFallback = true;
                return restSearchDocs(keyword, limit);
            }
            throw e;
        }
    }

    private List<Map<String, String>> mcpSearchDocs(String keyword, int limit) {
        JsonNode content = callTool("search", Map.of(
                "query", keyword == null ? "" : keyword,
                "top_k", Math.max(1, Math.min(limit, 10))));
        List<Map<String, String>> docs = new ArrayList<>();
        for (JsonNode item : textItems(content)) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("title", item.path("title").asText(
                    item.path("name").asText("未命名文档")));
            row.put("url", item.path("url").asText(item.path("slug").asText("")));
            row.put("summary", snippet(item.path("summary").asText(
                    item.path("description").asText(""))));
            docs.add(row);
        }
        return docs;
    }

    /**
     * 读取文档正文（read/ get 工具，工具名运行期发现；MCP 不可用时降级 REST v2）。
     */
    public String readDoc(String doc) {
        if (restFallback) {
            return restReadDoc(doc);
        }
        try {
            return mcpReadDoc(doc);
        } catch (IllegalStateException e) {
            if (String.valueOf(e.getMessage()).contains("403")) {
                restFallback = true;
                return restReadDoc(doc);
            }
            throw e;
        }
    }

    private String mcpReadDoc(String doc) {
        JsonNode content = callTool("read", Map.of("doc", doc == null ? "" : doc));
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : textItems(content)) {
            String text = item.path("text").asText("");
            if (!text.isBlank()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(text);
            }
        }
        String body = sb.length() > MAX_TEXT_CHARS
                ? sb.substring(0, MAX_TEXT_CHARS) + "…（正文过长已截断）" : sb.toString();
        if (body.isBlank()) {
            throw new IllegalStateException("语雀文档内容为空");
        }
        return body;
    }

    // ==================== MCP 协议 ====================

    private JsonNode callTool(String semantic, Map<String, Object> args) {
        String toolName = resolveToolName(semantic);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", args);
        JsonNode result = rpc("tools/call", params);
        if (result.path("isError").asBoolean(false)) {
            throw new IllegalStateException("语雀 MCP 工具调用失败，请稍后重试");
        }
        return result.path("content");
    }

    /**
     * 按语义（search/read）发现 MCP 工具名，缓存 10 分钟。
     */
    private String resolveToolName(String semantic) {
        for (String name : listToolNames()) {
            String normalized = name.toLowerCase();
            if (semantic.equals("search") && normalized.contains("search")) return name;
            if (semantic.equals("read") && (normalized.contains("read") || normalized.contains("get"))) {
                return name;
            }
        }
        throw new IllegalStateException("语雀 MCP 工具不可用");
    }

    private List<String> listToolNames() {
        List<String> names = new ArrayList<>();
        JsonNode tools = rpc("tools/list", Map.of()).path("tools");
        if (tools.isArray()) {
            tools.forEach(tool -> {
                String name = tool.path("name").asText("");
                if (!name.isBlank()) names.add(name);
            });
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("语雀 MCP 工具不可用");
        }
        return names;
    }

    /**
     * 发送一次 JSON-RPC 请求：先试 streamable HTTP，非 200 且非鉴权错误时回退 SSE 端点。
     */
    private JsonNode rpc(String method, Map<String, Object> params) {
        String token = token();
        String url = mcpUrl();
        if (!StringUtils.hasText(token) || !StringUtils.hasText(url)) {
            throw new IllegalStateException("语雀连接器尚未完成配置");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.put("params", params == null ? Map.of() : params);
        try {
            String payload = objectMapper.writeValueAsString(body);
            JsonNode response = postRpc(url, payload, token);
            if (response != null) return response;
            String sseUrl = url.endsWith("/mcp") ? url.substring(0, url.length() - 4) + "/sse" : url;
            response = postRpc(sseUrl, payload, token);
            if (response == null) {
                throw new IllegalStateException("语雀 MCP 响应格式异常");
            }
            return response;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("语雀 MCP 调用失败: {}", ex.getMessage());
            throw new IllegalStateException("语雀服务暂时不可用，请稍后重试");
        }
    }

    /**
     * 返回 result 节点；鉴权失败抛固定文案；HTTP 200 但响应体不是 JSON-RPC（SSE 网关）返回 null 触发回退。
     */
    private JsonNode postRpc(String url, String payload, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalStateException("语雀认证失败，请检查访问 Token 配置");
        }
        if (response.statusCode() == 429) {
            throw new IllegalStateException("语雀请求过于频繁，请稍后重试");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("语雀 MCP 调用失败(" + response.statusCode() + ")");
        }
        String bodyText = response.body() == null ? "" : response.body().trim();
        if (!bodyText.startsWith("{")) {
            // SSE 流式响应：取第一个 data: 行的 JSON
            int data = bodyText.indexOf("data:");
            if (data < 0) {
                return null;
            }
            bodyText = bodyText.substring(data + 5).trim();
            if (!bodyText.startsWith("{")) {
                return null;
            }
        }
        JsonNode root = objectMapper.readTree(bodyText);
        if (root.has("error")) {
            throw new IllegalStateException("语雀 MCP 调用失败，请稍后重试");
        }
        return root.path("result");
    }

    private List<JsonNode> textItems(JsonNode content) {
        List<JsonNode> items = new ArrayList<>();
        if (content == null) return items;
        if (content.isArray()) {
            content.forEach(items::add);
        } else if (content.isObject()) {
            items.add(content);
        }
        return items;
    }

    private String snippet(String value) {
        if (value == null) return "";
        return value.length() > 120 ? value.substring(0, 120) + "…" : value;
    }

    private String token() {
        return configService.getValue(GROUP, "yuque.token", null);
    }

    private String mcpUrl() {
        return configService.getValue(GROUP, "yuque.mcp-url", "https://mcp.yuque.com/mcp");
    }

    private int timeoutSeconds() {
        int value;
        try {
            value = Integer.parseInt(configService.getValue(GROUP, "yuque.timeout-seconds", "30"));
        } catch (NumberFormatException e) {
            value = 30;
        }
        return Math.min(Math.max(value, 5), 60);
    }

    // ==================== REST v2 降级（MCP 网关 403 时） ====================

    /** GET 语雀 REST v2（同一 Token，X-Auth-Token 头）。 */
    private JsonNode restGet(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(YUQUE_API_BASE + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds()))
                    .header("Accept", "application/json")
                    .header("X-Auth-Token", token())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalStateException("语雀认证失败，请检查访问 Token 配置");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("语雀接口调用失败(" + response.statusCode() + ")");
            }
            return objectMapper.readTree(response.body() == null ? "{}" : response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("语雀服务暂时不可用，请稍后重试");
        }
    }

    /** REST 探活：/user 只验证 Token 有效性。 */
    private void restUserProbe() {
        JsonNode root = restGet("/user");
        if (root.path("data").path("id").asLong(0) <= 0) {
            throw new IllegalStateException("语雀 Token 校验失败");
        }
    }

    /** REST 搜索：/search?q=&type=doc；url 字段形如 /{userLogin}/{bookSlug}/{docSlug}。 */
    private List<Map<String, String>> restSearchDocs(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            throw new IllegalStateException("缺少搜索关键词");
        }
        String q = java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = restGet("/search?q=" + q + "&type=doc&limit="
                + Math.max(1, Math.min(limit, 10)));
        List<Map<String, String>> docs = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("title", item.path("title").asText("未命名文档"));
            String url = item.path("url").asText("");
            row.put("url", url.startsWith("/") ? "https://www.yuque.com" + url : url);
            row.put("summary", snippet(item.path("summary").asText("")));
            // REST 读取需要 bookSlug/docSlug，缓存进 url 供 readDoc 解析
            docs.add(row);
            if (docs.size() >= limit) break;
        }
        return docs;
    }

    /**
     * REST 读取：doc 参数为文档 URL（https://xxx.yuque.com/{user}/{book}/{doc}）或
     * "{book}/{doc}" 或 "{user}/{book}/{doc}"；统一走 /repos/{book}/docs/{doc}。
     */
    private String restReadDoc(String doc) {
        String[] slugs = parseDocSlugs(doc);
        JsonNode root = restGet("/repos/" + slugs[0] + "/docs/" + slugs[1]);
        JsonNode data = root.path("data");
        String body = data.path("body").asText("");
        if (body.length() > MAX_TEXT_CHARS) {
            body = body.substring(0, MAX_TEXT_CHARS) + "…（正文过长已截断）";
        }
        if (body.isBlank()) {
            throw new IllegalStateException("语雀文档内容为空");
        }
        return body;
    }

    private String[] parseDocSlugs(String doc) {
        String cleaned = doc == null ? "" : doc.trim();
        if (cleaned.startsWith("http")) {
            java.net.URI uri = URI.create(cleaned);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String[] parts = path.replaceAll("^/+", "").split("/");
            if (parts.length >= 3) {
                return new String[]{parts[parts.length - 2], parts[parts.length - 1]};
            }
            throw new IllegalStateException("无法从 URL 解析知识库/文档 slug：" + doc);
        }
        String[] parts = cleaned.split("/");
        if (parts.length == 2) {
            return new String[]{parts[0], parts[1]};
        }
        if (parts.length == 3) {
            return new String[]{parts[1], parts[2]};
        }
        throw new IllegalStateException("doc 参数格式无效，应为文档 URL 或 知识库/文档 slug");
    }
}
