package com.bu.management.integration;

import com.bu.management.config.SeeyonOaRuntimeConfig;
import com.bu.management.service.SeeyonOaConfigService;
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
 * 致远互联 Seeyon OA REST API 客户端
 * 用于与 OA 系统交互，获取组织架构、待办事项、流程数据等
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeeyonOaClient {

    private static final String REST_PATH = "/seeyon/rest";

    private final SeeyonOaConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 缓存的 token，避免每次请求都重新获取 */
    private volatile String cachedToken;
    private volatile long tokenExpireTime;

    // ==================== 认证 ====================

    /**
     * 获取 REST API 访问令牌
     * POST /seeyon/rest/token
     */
    public String obtainToken() {
        SeeyonOaRuntimeConfig config = configService.getRuntimeConfig();
        if (!config.hasCredentials()) {
            throw new IllegalStateException("OA 集成尚未完成配置");
        }

        // 如果已有缓存的 token 且未过期，直接返回
        if (StringUtils.hasText(cachedToken) && System.currentTimeMillis() < tokenExpireTime) {
            return cachedToken;
        }

        Map<String, String> body = new LinkedHashMap<>();
        body.put("userName", config.username());
        body.put("password", config.password());

        JsonNode response = sendJsonPost(config, REST_PATH + "/token", body);
        String token = response.path("id").asText();
        if (!StringUtils.hasText(token)) {
            token = response.path("token").asText();
        }
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("OA 认证失败：" + response.path("message").asText("未知错误"));
        }

        // 缓存 token，默认 30 分钟
        cachedToken = token;
        tokenExpireTime = System.currentTimeMillis() + 30 * 60 * 1000;
        return token;
    }

    /**
     * 连接测试：获取当前用户信息
     */
    public JsonNode getCurrentUser() {
        SeeyonOaRuntimeConfig config = configService.getRuntimeConfig();
        if (!config.isConfigured()) {
            throw new IllegalStateException("请先配置 OA 连接参数");
        }
        return sendAuthenticatedGet(config, REST_PATH + "/api/orgMember/current");
    }

    // ==================== 组织架构 ====================

    /**
     * 获取所有部门
     * GET /seeyon/rest/api/orgDepartment
     */
    public List<JsonNode> listDepartments() {
        SeeyonOaRuntimeConfig config = configuredRuntime();
        return paginatedGet(config, REST_PATH + "/api/orgDepartment", "department");
    }

    /**
     * 获取所有人员
     * GET /seeyon/rest/api/orgMember
     */
    public List<JsonNode> listMembers() {
        SeeyonOaRuntimeConfig config = configuredRuntime();
        return paginatedGet(config, REST_PATH + "/api/orgMember", "member");
    }

    /**
     * 根据部门获取人员
     * GET /seeyon/rest/api/orgMember?departmentId={id}
     */
    public List<JsonNode> listMembersByDepartment(String departmentId) {
        SeeyonOaRuntimeConfig config = configuredRuntime();
        return paginatedGet(config, REST_PATH + "/api/orgMember?departmentId=" + departmentId, "member");
    }

    /**
     * 获取所有岗位
     * GET /seeyon/rest/api/orgPost
     */
    public List<JsonNode> listPosts() {
        SeeyonOaRuntimeConfig config = configuredRuntime();
        return paginatedGet(config, REST_PATH + "/api/orgPost", "post");
    }

    /**
     * 获取所有职级
     * GET /seeyon/rest/api/orgLevel
     */
    public List<JsonNode> listLevels() {
        SeeyonOaRuntimeConfig config = configuredRuntime();
        return paginatedGet(config, REST_PATH + "/api/orgLevel", "level");
    }

    // ==================== 待办/流程 ====================

    /**
     * 获取当前用户的待办事项
     * GET /seeyon/rest/api/affair/pending
     */
    public List<JsonNode> listPendingAffairs() {
        SeeyonOaRuntimeConfig config = configuredRuntime();
        return paginatedGet(config, REST_PATH + "/api/affair/pending", "affair");
    }

    /**
     * 获取已办事项
     * GET /seeyon/rest/api/affair/done
     */
    public List<JsonNode> listDoneAffairs() {
        SeeyonOaRuntimeConfig config = configuredRuntime();
        return paginatedGet(config, REST_PATH + "/api/affair/done", "affair");
    }

    /**
     * 获取流程实例详情
     * GET /seeyon/rest/api/flow/{flowId}
     */
    public JsonNode getFlowDetail(String flowId) {
        SeeyonOaRuntimeConfig config = configuredRuntime();
        return sendAuthenticatedGet(config, REST_PATH + "/api/flow/" + flowId);
    }

    /**
     * 获取表单数据
     * GET /seeyon/rest/api/form/{formId}
     */
    public JsonNode getFormData(String formId) {
        SeeyonOaRuntimeConfig config = configuredRuntime();
        return sendAuthenticatedGet(config, REST_PATH + "/api/form/" + formId);
    }

    // ==================== 内部 HTTP 方法 ====================

    private List<JsonNode> paginatedGet(SeeyonOaRuntimeConfig config, String path, String dataKey) {
        List<JsonNode> all = new ArrayList<>();
        int page = 1;
        while (true) {
            String pagedPath = path + (path.contains("?") ? "&" : "?") + "page=" + page + "&size=200";
            JsonNode response = sendAuthenticatedGet(config, pagedPath);
            JsonNode data = response;
            if (response.has("data")) {
                data = response.get("data");
            }
            if (dataKey != null && data.has(dataKey)) {
                data = data.get(dataKey);
            }
            if (data == null || !data.isArray()) {
                // 非分页响应，直接返回
                if (response.isArray()) {
                    response.forEach(all::add);
                } else {
                    all.add(response);
                }
                return all;
            }
            data.forEach(all::add);
            if (data.size() < 200) {
                return all;
            }
            page++;
        }
    }

    private JsonNode sendAuthenticatedGet(SeeyonOaRuntimeConfig config, String path) {
        return sendJsonRequest(config, "GET", path, null, 1);
    }

    private JsonNode sendJsonPost(SeeyonOaRuntimeConfig config, String path, Object body) {
        return sendJsonRequest(config, "POST", path, body, 1);
    }

    private JsonNode sendJsonRequest(SeeyonOaRuntimeConfig config, String method,
                                      String path, Object body, int attempt) {
        try {
            String payload = body == null ? null : objectMapper.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.effectiveBaseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");

            // 非 token 请求时，携带认证 token
            if (!path.endsWith("/token") && StringUtils.hasText(cachedToken)) {
                builder.header("token", cachedToken);
            }

            HttpRequest request;
            if ("POST".equals(method)) {
                request = builder.POST(HttpRequest.BodyPublishers.ofString(
                        payload == null ? "" : payload)).build();
            } else {
                request = builder.GET().build();
            }

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                // token 过期，清除缓存重试
                cachedToken = null;
                if (attempt < 2 && !path.endsWith("/token")) {
                    obtainToken();
                    return sendJsonRequest(config, method, path, body, attempt + 1);
                }
                throw new IllegalStateException("OA 认证失败，请检查用户名和密码");
            }

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String responseBody = response.body();
                if (responseBody == null || responseBody.isBlank()) {
                    return objectMapper.createObjectNode();
                }
                return objectMapper.readTree(responseBody);
            }

            if (attempt < 3 && isRetryable(response.statusCode())) {
                Thread.sleep(250L * (1L << (attempt - 1)));
                return sendJsonRequest(config, method, path, body, attempt + 1);
            }

            String responseBody = response.body() == null ? "" : response.body();
            throw new IllegalStateException("OA 接口调用失败(" + response.statusCode() + "): "
                    + responseBody.substring(0, Math.min(responseBody.length(), 500)));

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OA 接口调用被中断", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("OA 接口调用失败: " + ex.getMessage(), ex);
        }
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private SeeyonOaRuntimeConfig configuredRuntime() {
        SeeyonOaRuntimeConfig config = configService.getRuntimeConfig();
        if (!config.isConfigured()) {
            throw new IllegalStateException("OA 集成尚未完成配置");
        }
        // 确保有 token
        if (!StringUtils.hasText(cachedToken)) {
            obtainToken();
        }
        return config;
    }
}