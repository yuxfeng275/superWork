package com.bu.management.integration;

import com.bu.management.config.WorktimeRuntimeConfig;
import com.bu.management.service.WorktimeConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工时系统（worktime.lucidata.cn）REST API 客户端。
 * 认证：POST /api/v1/auth/login {employee_no, password} → data.token（JWT，Bearer 携带）。
 * 业务响应信封：{code:200, message, data}；code != 200 视为失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorktimeApiClient {

    private static final String API = "/api/v1";
    private static final int PAGE_SIZE = 100;

    private final WorktimeConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String cachedToken;
    private volatile long tokenExpireTime;

    // ==================== 认证 ====================

    public String obtainToken() {
        WorktimeRuntimeConfig config = configService.getRuntimeConfig();
        if (!config.hasCredentials()) {
            throw new IllegalStateException("工时系统集成尚未完成配置");
        }
        if (StringUtils.hasText(cachedToken) && System.currentTimeMillis() < tokenExpireTime) {
            return cachedToken;
        }
        JsonNode response = sendJson(config, "POST", API + "/auth/login",
                Map.of("employee_no", config.employeeNo(), "password", config.password()), false, 1);
        JsonNode data = unwrap(response);
        String token = data.path("token").asText(null);
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("工时系统认证失败：未返回 token");
        }
        cachedToken = token;
        // JWT 默认有效期较长，缓存 12 小时；401 时会自动重登
        tokenExpireTime = System.currentTimeMillis() + 12L * 60 * 60 * 1000;
        return token;
    }

    /** 连接测试：登录 + 拉取销售报表筛选器（含账号可见业务线范围） */
    public JsonNode testConnection() {
        WorktimeRuntimeConfig config = configuredRuntime();
        return unwrap(sendJson(config, "GET", API + "/contracts/sales-report/filters", null, true, 1));
    }

    // ==================== 合同明细（销售报表） ====================

    /** 按业务线分页拉取合同明细（drilldown），返回 items 数组 */
    public List<JsonNode> fetchAllContractDetails(int year, String businessLineName) {
        WorktimeRuntimeConfig config = configuredRuntime();
        List<JsonNode> all = new ArrayList<>();
        int page = 1;
        while (true) {
            String path = API + "/contracts/sales-report/drilldown?year=" + year
                    + "&drill_type=business_line&name=" + urlEncode(businessLineName)
                    + "&page=" + page + "&page_size=" + PAGE_SIZE;
            JsonNode data = unwrap(sendJson(config, "GET", path, null, true, 1));
            JsonNode items = data.path("items");
            if (items.isArray()) {
                items.forEach(all::add);
            }
            long total = data.path("total").asLong(0);
            if ((long) page * PAGE_SIZE >= total || items.isEmpty()) {
                return all;
            }
            page++;
        }
    }

    // ==================== 成本分析 ====================

    /** 成本分析-项目视角，分页返回 items */
    public List<JsonNode> fetchAllCostByProject(String fromMonth, String toMonth) {
        WorktimeRuntimeConfig config = configuredRuntime();
        List<JsonNode> all = new ArrayList<>();
        int page = 1;
        while (true) {
            String path = API + "/finance/analysis/cost/project?from_month=" + fromMonth
                    + "&to_month=" + toMonth + "&page=" + page + "&page_size=" + PAGE_SIZE;
            JsonNode data = unwrap(sendJson(config, "GET", path, null, true, 1));
            JsonNode items = data.path("items");
            if (items.isArray()) {
                items.forEach(all::add);
            }
            long total = data.path("total").asLong(0);
            if ((long) page * PAGE_SIZE >= total || items.isEmpty()) {
                return all;
            }
            page++;
        }
    }

    // ==================== 工时 ====================

    /** 工时月度确认状态列表：[{year_month, status, status_label, deadline}] */
    public JsonNode fetchAvailableMonths() {
        WorktimeRuntimeConfig config = configuredRuntime();
        return unwrap(sendJson(config, "GET", API + "/worktime/available-months", null, true, 1));
    }

    /** 下载工时业务线明细 xlsx（与手工导入模板同格式） */
    public byte[] downloadWorklogBlDetail(List<Long> worktimeBusinessLineIds, String yearMonth) {
        WorktimeRuntimeConfig config = configuredRuntime();
        Map<String, Object> body = Map.of("business_line_ids", worktimeBusinessLineIds, "year_month", yearMonth);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.effectiveBaseUrl() + API + "/worktime/export/bl-detail"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + obtainToken())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() == 200 && contentType.contains("spreadsheetml")) {
                return response.body();
            }
            // 失败时返回的是 JSON 错误
            String errorBody = new String(response.body(), StandardCharsets.UTF_8);
            throw new IllegalStateException("工时明细导出失败(" + response.statusCode() + "): "
                    + errorBody.substring(0, Math.min(errorBody.length(), 300)));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工时明细导出被中断", ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("工时明细导出失败: " + ex.getMessage(), ex);
        }
    }

    // ==================== 内部方法 ====================

    private WorktimeRuntimeConfig configuredRuntime() {
        WorktimeRuntimeConfig config = configService.getRuntimeConfig();
        if (!config.isConfigured()) {
            throw new IllegalStateException("工时系统集成未启用或未配置账号");
        }
        return config;
    }

    /** 解信封：code==200 返回 data，否则抛错 */
    private JsonNode unwrap(JsonNode response) {
        int code = response.path("code").asInt(-1);
        if (code == 200) {
            return response.path("data");
        }
        String message = response.path("message").asText(response.path("detail").asText("未知错误"));
        throw new IllegalStateException("工时系统接口返回错误(" + code + "): " + message);
    }

    private JsonNode sendJson(WorktimeRuntimeConfig config, String method, String path,
                              Object body, boolean authenticated, int attempt) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.effectiveBaseUrl() + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json");
            if (authenticated) {
                builder.header("Authorization", "Bearer " + obtainToken());
            }
            if ("POST".equals(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(
                        body == null ? "" : objectMapper.writeValueAsString(body)));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 && authenticated && attempt < 2) {
                cachedToken = null;
                return sendJson(config, method, path, body, authenticated, attempt + 1);
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String responseBody = response.body();
                if (responseBody == null || responseBody.isBlank()) {
                    return objectMapper.createObjectNode();
                }
                return objectMapper.readTree(responseBody);
            }
            if (attempt < 3 && (response.statusCode() == 429 || response.statusCode() >= 500)) {
                Thread.sleep(250L * (1L << (attempt - 1)));
                return sendJson(config, method, path, body, authenticated, attempt + 1);
            }
            String responseBody = response.body() == null ? "" : response.body();
            throw new IllegalStateException("工时系统接口调用失败(" + response.statusCode() + "): "
                    + responseBody.substring(0, Math.min(responseBody.length(), 500)));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工时系统接口调用被中断", ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("工时系统接口调用失败: " + ex.getMessage(), ex);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
