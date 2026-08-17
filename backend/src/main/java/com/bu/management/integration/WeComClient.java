package com.bu.management.integration;

import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.service.EmailIntegrationConfigService;
import com.bu.management.entity.EmailDailyDigest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WeComClient {
  private final EmailIntegrationConfigService configService;
  private final ObjectMapper objectMapper;
  private final HttpClient client;

  public WeComClient(EmailIntegrationConfigService configService, ObjectMapper objectMapper) {
    this.configService = configService;
    this.objectMapper = objectMapper;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public void push(String userId, EmailDailyDigest digest, String detailUrl) {
    EmailIntegrationRuntimeConfig config = configService.getRuntimeConfig();
    if (!config.isWeComConfigured()) {
      throw new IllegalStateException("企业微信未配置");
    }
    try {
      JsonNode token =
          get(config.weComBaseUrl(),
              "/cgi-bin/gettoken?corpid="
                  + encode(config.weComCorpId())
                  + "&corpsecret="
                  + encode(config.weComSecret()));
      requireSuccess(token, "企业微信令牌获取失败");
      String important = digest.getImportantItems();
      String content =
          digest.getOverview()
              + "\n重要事项："
              + (important == null ? "[]" : important)
              + (detailUrl == null || detailUrl.isBlank() ? "" : "\n详情：" + detailUrl);
      Map<String, Object> body =
          Map.of(
              "touser",
              userId,
              "msgtype",
              "text",
              "agentid",
              Long.parseLong(config.weComAgentId()),
              "text",
              Map.of("content", content),
              "safe",
              0);
      JsonNode response =
          post(config.weComBaseUrl(),
              "/cgi-bin/message/send?access_token=" + encode(token.path("access_token").asText()),
              body);
      requireSuccess(response, "企业微信推送失败");
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException stateException) {
        throw stateException;
      }
      throw new IllegalStateException("企业微信推送失败", exception);
    }
  }

  public void testConnection() {
    EmailIntegrationRuntimeConfig config = configService.getRuntimeConfig();
    if (!config.isWeComConfigured()) {
      throw new IllegalStateException("企业微信未配置或未启用");
    }
    try {
      JsonNode token = get(config.weComBaseUrl(),
          "/cgi-bin/gettoken?corpid=" + encode(config.weComCorpId())
              + "&corpsecret=" + encode(config.weComSecret()));
      requireSuccess(token, "企业微信连接测试失败");
      if (!token.path("access_token").isTextual()) {
        throw new IllegalStateException("企业微信连接测试响应无效");
      }
    } catch (IllegalStateException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("企业微信连接测试失败", exception);
    }
  }

  private void requireSuccess(JsonNode response, String message) {
    if (response.path("errcode").asInt(-1) != 0) {
      throw new IllegalStateException(message + "(" + response.path("errcode").asInt() + ")");
    }
  }

  private JsonNode get(String baseUrl, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + path))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body());
  }

  private JsonNode post(String baseUrl, String path, Object body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    return objectMapper.readTree(response.body());
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
