package com.bu.management.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Bark（iOS 推送）客户端：POST {server}/{device-key}，按人/按组推一条通知。
 *
 * <p>device-key 只从环境变量读（bark.device-key），绝不落仓库；日志里也不出现它。
 * 推送失败只记 warn 返回 false，不抛异常——晨报推送是尽力而为。
 */
@Slf4j
@Component
public class BarkClient {

    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Value("${bark.enabled:false}")
    private boolean enabled;

    @Value("${bark.server:https://api.day.app}")
    private String server;

    @Value("${bark.device-key:}")
    private String deviceKey;

    @Value("${bark.group:合拍}")
    private String group;

    @Value("${bark.sound:}")
    private String sound;

    public BarkClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 开关打开且填了 device-key 才算可用。 */
    public boolean isConfigured() {
        return enabled && StringUtils.hasText(deviceKey);
    }

    /** 推送一条通知；返回是否成功。异常不外抛。 */
    public boolean push(String title, String body, String url) {
        if (!isConfigured()) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title == null ? "" : title);
        payload.put("body", body == null ? "" : body);
        if (StringUtils.hasText(group)) {
            payload.put("group", group);
        }
        if (StringUtils.hasText(sound)) {
            payload.put("sound", sound);
        }
        if (StringUtils.hasText(url)) {
            payload.put("url", url);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(trimSlash(server) + "/" + deviceKey.trim()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Bark 推送失败: httpStatus={}", response.statusCode());
                return false;
            }
            JsonNode json = objectMapper.readTree(response.body());
            int code = json.path("code").asInt(-1);
            if (code != 200) {
                log.warn("Bark 推送失败: code={}, message={}", code, mask(json.path("message").asText("")));
                return false;
            }
            return true;
        } catch (Exception exception) {
            log.warn("Bark 推送异常: {}", mask(exception.getMessage()));
            return false;
        }
    }

    /** 日志脱敏：绝不把 device key 写进日志。 */
    private String mask(String text) {
        if (text == null) {
            return "";
        }
        return StringUtils.hasText(deviceKey) ? text.replace(deviceKey.trim(), "***") : text;
    }

    private String trimSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
