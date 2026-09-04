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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工时系统（worktime.lucidata.cn）只读客户端。
 * 服务账号登录（POST /api/v1/auth/login，Bearer token 内存缓存，401 重登一次），
 * 仅暴露查询端点；任何错误消息不回显账号密码。
 * 端点结构已按其前端 bundle 核实：/worktime/employee/{employeeId}/{month} 返回
 * {year_month, status, status_label, total_hours, detail_count, submitted_at, details[]}。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorktimeClient {

    private static final String GROUP = "ai-connector";

    private final SystemConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String cachedToken;

    public boolean enabled() {
        return configService.getBoolean(GROUP, "worktime.enabled", false);
    }

    public boolean configured() {
        return StringUtils.hasText(username()) && StringUtils.hasText(password()) && StringUtils.hasText(baseUrl());
    }

    /**
     * 连接测试：登录成功即视为可用。
     */
    public void testConnection() {
        login();
    }

    /**
     * 查询某员工某月工时汇总与明细（只读）。
     *
     * @param employeeId 工时系统员工 ID（来自身份映射）
     * @param month      YYYY-MM
     */
    public JsonNode employeeMonthly(String employeeId, String month) {
        JsonNode response = authenticatedGet("/api/v1/worktime/employee/" + employeeId + "/" + month);
        if (response == null || !response.isObject()) {
            throw new IllegalStateException("工时系统返回格式异常");
        }
        return response;
    }

    /**
     * 查询当前员工的历史月度列表（year_month/status/total_hours/...）。
     */
    public JsonNode history(int page, int pageSize) {
        return authenticatedGet("/api/v1/worktime/history?page=" + page + "&page_size=" + pageSize);
    }

    /**
     * 可用工时月份列表。
     */
    public JsonNode availableMonths() {
        return authenticatedGet("/api/v1/worktime/available-months");
    }

    /**
     * 把返回 JSON 压平成给模型看的月度摘要文本（不含凭据）。
     */
    public String renderMonthly(JsonNode data, String month) {
        StringBuilder sb = new StringBuilder("工时系统 ").append(month).append(" 月度汇总：");
        String status = data.path("status_label").asText(data.path("status").asText(""));
        if (!status.isBlank()) sb.append("状态=").append(status);
        sb.append("，合计=").append(data.path("total_hours").asText("0")).append(" 小时");
        if (data.hasNonNull("detail_count")) {
            sb.append("，条目数=").append(data.path("detail_count").asInt());
        }
        if (data.hasNonNull("submitted_at")) {
            sb.append("，提交时间=").append(data.path("submitted_at").asText());
        }
        int shown = 0;
        for (JsonNode item : data.path("details")) {
            if (shown >= 20) {
                sb.append("\n…（明细过多已截断）");
                break;
            }
            sb.append("\n- 项目=").append(item.path("project_name").asText("未命名"));
            String businessLine = item.path("business_line_name").asText("");
            if (!businessLine.isBlank()) sb.append("，业务线=").append(businessLine);
            sb.append("，工时=").append(item.path("hours").asText("0"));
            String remark = item.path("remark").asText("");
            if (!remark.isBlank()) sb.append("，说明=").append(truncate(remark, 80));
            shown++;
        }
        if (shown == 0) {
            sb.append("，本月暂无填报明细");
        }
        return sb.toString();
    }

    /**
     * 校验月份格式 YYYY-MM；非法时抛出带固定文案的异常。
     */
    public String normalizeMonth(String month) {
        if (!StringUtils.hasText(month)) {
            return LocalDate.now().toString().substring(0, 7);
        }
        try {
            return LocalDate.parse(month + "-01").toString().substring(0, 7);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("月份格式无效，请使用 YYYY-MM");
        }
    }

    // ==================== HTTP ====================

    private JsonNode authenticatedGet(String path) {
        HttpResponse<String> response = send(path, cachedToken);
        if (response.statusCode() == 401) {
            cachedToken = null;
            login();
            response = send(path, cachedToken);
        }
        if (response.statusCode() == 404) {
            throw new IllegalArgumentException("工时系统未找到对应数据（员工或月份不存在）");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("工时系统暂时不可用(" + response.statusCode() + ")，请稍后重试");
        }
        return parseBody(response.body());
    }

    private synchronized void login() {
        if (StringUtils.hasText(cachedToken)) {
            return;
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("employee_no", username());
        body.put("password", password());
        HttpResponse<String> response;
        try {
            String payload = objectMapper.writeValueAsString(body);
            response = send("/api/v1/auth/login", null, payload);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("工时系统登录失败: {}", ex.getMessage());
            throw new IllegalStateException("工时系统暂时不可用，请稍后重试");
        }
        if (response.statusCode() == 401 || response.statusCode() == 403
                || response.statusCode() == 400 || response.statusCode() == 422) {
            throw new IllegalStateException("工时系统认证失败，请检查服务账号配置");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("工时系统暂时不可用(" + response.statusCode() + ")，请稍后重试");
        }
        JsonNode root = parseBody(response.body());
        String token = root.path("token").asText(root.path("access_token").asText(""));
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("工时系统认证失败，请检查服务账号配置");
        }
        cachedToken = token;
    }

    private HttpResponse<String> send(String path, String token) {
        return send(path, token, null);
    }

    private HttpResponse<String> send(String path, String token, String payload) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(baseUrl()) + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds()))
                    .header("Accept", "application/json");
            if (StringUtils.hasText(token)) {
                builder.header("Authorization", "Bearer " + token);
            }
            if (payload != null) {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
            } else {
                builder.GET();
            }
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("工时系统调用失败: {}", ex.getMessage());
            throw new IllegalStateException("工时系统暂时不可用，请稍后重试");
        }
    }

    private JsonNode parseBody(String body) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            // 统一返回包装 {code, data, message} 或直接数据
            return root.has("data") && (root.path("data").isObject() || root.path("data").isArray())
                    ? root.path("data") : root;
        } catch (Exception e) {
            throw new IllegalStateException("工时系统返回格式异常");
        }
    }

    private String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }

    private String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String username() {
        return configService.getValue(GROUP, "worktime.username", null);
    }

    private String password() {
        return configService.getValue(GROUP, "worktime.password", null);
    }

    private String baseUrl() {
        return configService.getValue(GROUP, "worktime.base-url", "https://worktime.lucidata.cn");
    }

    private int timeoutSeconds() {
        int value;
        try {
            value = Integer.parseInt(configService.getValue(GROUP, "worktime.timeout-seconds", "30"));
        } catch (NumberFormatException e) {
            value = 30;
        }
        return Math.min(Math.max(value, 5), 60);
    }
}
