package com.bu.management.integration;

import com.bu.management.config.YunxiaoRuntimeConfig;
import com.bu.management.service.YunxiaoConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class YunxiaoClient {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final YunxiaoConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public List<JsonNode> searchWorkitems(String projectId, String category) {
        return searchWorkitems(projectId, category, null);
    }

    public List<JsonNode> searchWorkitems(String projectId, String category,
                                          LocalDate modifiedSince) {
        YunxiaoRuntimeConfig config = configuredRuntime();
        List<JsonNode> all = new ArrayList<>();
        int page = 1;
        while (true) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("category", category);
            if (modifiedSince != null) {
                body.put("conditions", modifiedSinceConditions(modifiedSince));
            }
            body.put("orderBy", "gmtCreate");
            body.put("page", page);
            body.put("perPage", 200);
            body.put("sort", "desc");
            body.put("spaceId", projectId);
            body.put("spaceType", "Project");
            JsonNode response = sendJson(
                    config, "POST", apiPath(config, "/workitems:search"), body);
            if (!response.isArray()) {
                throw new IllegalStateException("云效工作项搜索返回格式异常");
            }
            response.forEach(all::add);
            if (response.size() < 200) {
                return all;
            }
            page++;
        }
    }

    private String modifiedSinceConditions(LocalDate modifiedSince) {
        ObjectNode filter = objectMapper.createObjectNode();
        filter.put("fieldIdentifier", "gmtModified");
        filter.put("operator", "BETWEEN");
        filter.putArray("value").add(modifiedSince + " 00:00:00");
        filter.put("toValue", LocalDate.now(BUSINESS_ZONE) + " 23:59:59");
        filter.put("className", "dateTime");
        filter.put("format", "input");
        ObjectNode conditions = objectMapper.createObjectNode();
        conditions.putArray("conditionGroups").addArray().add(filter);
        return conditions.toString();
    }

    public List<JsonNode> searchProjects() {
        YunxiaoRuntimeConfig config = connectedRuntime();
        List<JsonNode> all = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode response = sendJson(config, "POST", apiPath(config, "/projects:search"), Map.of(
                    "orderBy", "gmtCreate",
                    "page", page,
                    "perPage", 200,
                    "sort", "desc"
            ));
            if (!response.isArray()) {
                throw new IllegalStateException("云效项目搜索返回格式异常");
            }
            response.forEach(all::add);
            if (response.size() < 200) {
                return all;
            }
            page++;
        }
    }

    public List<JsonNode> searchMembers() {
        YunxiaoRuntimeConfig config = connectedRuntime();
        List<JsonNode> all = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode response = sendJson(config, "POST", platformPath(config, "/members:search"), Map.of(
                    "page", page,
                    "perPage", 100,
                    "statuses", List.of("ENABLED")
            ));
            if (!response.isArray()) {
                throw new IllegalStateException("云效成员搜索返回格式异常");
            }
            response.forEach(all::add);
            if (response.size() < 100) {
                return all;
            }
            page++;
        }
    }

    public List<JsonNode> listEffortRecords(String workitemId) {
        YunxiaoRuntimeConfig config = configuredRuntime();
        return arrayResponse(config, "GET",
                apiPath(config, "/workitems/" + workitemId + "/effortRecords"));
    }

    public List<JsonNode> listEstimatedEfforts(String workitemId) {
        YunxiaoRuntimeConfig config = configuredRuntime();
        return arrayResponse(config, "GET",
                apiPath(config, "/workitems/" + workitemId + "/estimatedEfforts"));
    }

    public JsonNode createWorkitem(String projectId, String workitemTypeId, String assignedTo,
                                   String subject, String description) {
        YunxiaoRuntimeConfig config = configuredRuntime();
        return sendJson(config, "POST", apiPath(config, "/workitems"), Map.of(
                "assignedTo", assignedTo,
                "description", description == null ? "" : description,
                "formatType", "MARKDOWN",
                "spaceId", projectId,
                "subject", subject,
                "workitemTypeId", workitemTypeId
        ));
    }

    public JsonNode getWorkitem(String workitemId) {
        YunxiaoRuntimeConfig config = configuredRuntime();
        return sendJson(config, "GET", apiPath(config, "/workitems/" + workitemId), null);
    }

    public JsonNode getCurrentUser() {
        YunxiaoRuntimeConfig config = configService.getRuntimeConfig();
        if (!config.hasCredentials()) {
            throw new IllegalStateException("请先保存云效服务地址和个人访问令牌");
        }
        return sendJson(config, "GET", "/oapi/v1/platform/user", null);
    }

    private List<JsonNode> arrayResponse(YunxiaoRuntimeConfig config, String method, String path) {
        JsonNode response = sendJson(config, method, path, null);
        if (!response.isArray()) {
            throw new IllegalStateException("云效工时接口返回格式异常");
        }
        List<JsonNode> result = new ArrayList<>();
        response.forEach(result::add);
        return result;
    }

    private JsonNode sendJson(YunxiaoRuntimeConfig config, String method, String path, Object body) {
        try {
            String payload = body == null ? null : objectMapper.writeValueAsString(body);
            for (int attempt = 1; attempt <= 3; attempt++) {
                HttpResponse<String> response = httpClient.send(
                        buildRequest(config, method, path, payload),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return objectMapper.readTree(response.body());
                }
                if (attempt < 3 && isRetryable(response.statusCode())) {
                    Thread.sleep(250L * (1L << (attempt - 1)));
                    continue;
                }
                String responseBody = response.body() == null ? "" : response.body();
                throw new IllegalStateException("云效接口调用失败(" + response.statusCode() + "): "
                        + responseBody.substring(0, Math.min(responseBody.length(), 500)));
            }
            throw new IllegalStateException("云效接口调用失败");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("云效接口调用被中断", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("云效接口调用失败: " + ex.getMessage(), ex);
        }
    }

    private HttpRequest buildRequest(YunxiaoRuntimeConfig config, String method,
                                     String path, String payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(config.baseUrl()) + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("x-yunxiao-token", config.token());
        return "POST".equals(method)
                ? builder.POST(HttpRequest.BodyPublishers.ofString(payload == null ? "" : payload)).build()
                : builder.GET().build();
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private String apiPath(YunxiaoRuntimeConfig config, String suffix) {
        if (config.isRegionEdition()) {
            return "/oapi/v1/projex" + suffix;
        }
        return "/oapi/v1/projex/organizations/" + config.organizationId() + suffix;
    }

    private String platformPath(YunxiaoRuntimeConfig config, String suffix) {
        if (config.isRegionEdition()) {
            return "/oapi/v1/platform" + suffix;
        }
        return "/oapi/v1/platform/organizations/" + config.organizationId() + suffix;
    }

    private String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private YunxiaoRuntimeConfig configuredRuntime() {
        YunxiaoRuntimeConfig config = configService.getRuntimeConfig();
        if (!config.isConfigured()) {
            throw new IllegalStateException("云效集成尚未完成配置");
        }
        return config;
    }

    private YunxiaoRuntimeConfig connectedRuntime() {
        YunxiaoRuntimeConfig config = configService.getRuntimeConfig();
        if (!config.hasConnectionParameters()) {
            throw new IllegalStateException("请先保存完整的云效连接参数");
        }
        return config;
    }
}
