package com.bu.management.integration;

import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.service.EmailIntegrationConfigService;
import com.bu.management.entity.EmailMessage;
import com.bu.management.service.DigestContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekDigestClient {
  private static final Set<String> REQUIRED =
      Set.of("overview", "importantItems", "todoItems", "riskItems", "replyItems");
  private final EmailIntegrationConfigService configService;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public DeepSeekDigestClient(EmailIntegrationConfigService configService, ObjectMapper objectMapper) {
    this.configService = configService;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public DigestContent generate(List<EmailMessage> messages, Long ownerUserId) {
    EmailIntegrationRuntimeConfig config = configService.getRuntimeConfig();
    if (!config.isDeepSeekConfigured()) {
      throw new IllegalStateException("DeepSeek 未配置");
    }
    try {
      Map<String, Object> requestBody = new LinkedHashMap<>();
      requestBody.put("model", config.deepSeekModel());
      requestBody.put("response_format", Map.of("type", "json_object"));
      requestBody.put("temperature", 0.1);
      requestBody.put(
          "messages",
          List.of(
              Map.of("role", "system", "content", systemPrompt()),
              Map.of("role", "user", "content", buildInput(messages, ownerUserId))));
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(trimSlash(config.deepSeekBaseUrl()) + "/chat/completions"))
              .timeout(Duration.ofSeconds(45))
              .header("Authorization", "Bearer " + config.deepSeekApiKey())
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("DeepSeek 调用失败(" + response.statusCode() + ")");
      }
      JsonNode envelope = objectMapper.readTree(response.body());
      String content =
          envelope.path("choices").path(0).path("message").path("content").asText(null);
      if (content == null) {
        throw new IllegalStateException("DeepSeek 返回缺少内容");
      }
      JsonNode digest = objectMapper.readTree(content);
      validate(digest, messages);
      return new DigestContent(
          digest.path("overview").asText(),
          objectMapper.writeValueAsString(digest.path("importantItems")),
          objectMapper.writeValueAsString(digest.path("todoItems")),
          objectMapper.writeValueAsString(digest.path("riskItems")),
          objectMapper.writeValueAsString(digest.path("replyItems")),
          false);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("DeepSeek 调用被中断", exception);
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException stateException) {
        throw stateException;
      }
      throw new IllegalStateException("DeepSeek 摘要响应无效", exception);
    }
  }

  public void testConnection() {
    EmailIntegrationRuntimeConfig config = configService.getRuntimeConfig();
    if (!config.isDeepSeekConfigured()) {
      throw new IllegalStateException("DeepSeek 未配置或未启用");
    }
    try {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(trimSlash(config.deepSeekBaseUrl()) + "/models"))
          .timeout(Duration.ofSeconds(20))
          .header("Authorization", "Bearer " + config.deepSeekApiKey())
          .GET()
          .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("DeepSeek 连接测试失败(" + response.statusCode() + ")");
      }
      JsonNode body = objectMapper.readTree(response.body());
      if (!body.path("data").isArray()) {
        throw new IllegalStateException("DeepSeek 连接测试响应无效");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("DeepSeek 连接测试被中断", exception);
    } catch (IllegalStateException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("DeepSeek 连接测试失败", exception);
    }
  }

  private String buildInput(List<EmailMessage> messages, Long ownerUserId) throws Exception {
    List<Map<String, Object>> input = new ArrayList<>();
    int used = 0;
    for (EmailMessage message : messages) {
      if (!ownerUserId.equals(message.getOwnerUserId())) {
        throw new IllegalStateException("摘要输入包含其他用户邮件");
      }
      String body = message.getBodyText() == null ? "" : message.getBodyText();
      int allowance = 120_000 - used;
      if (allowance <= 0) {
        break;
      }
      body = body.substring(0, Math.min(body.length(), allowance));
      used += body.length();
      input.add(
          Map.of(
              "messageId",
              message.getId(),
              "subject",
              message.getSubject() == null ? "" : message.getSubject(),
              "sender",
              message.getSenderAddress() == null ? "" : message.getSenderAddress(),
              "receivedAt",
              String.valueOf(message.getReceivedAt()),
              "bodyText",
              body));
    }
    return objectMapper.writeValueAsString(input);
  }

  private void validate(JsonNode digest, List<EmailMessage> messages) {
    if (!digest.isObject()
        || REQUIRED.stream().anyMatch(field -> !digest.has(field))
        || !digest.path("overview").isTextual()) {
      throw new IllegalStateException("DeepSeek 摘要 JSON 缺少必填字段");
    }
    Set<Long> ids =
        messages.stream().map(EmailMessage::getId).collect(java.util.stream.Collectors.toSet());
    for (String field : REQUIRED.stream().filter(name -> !"overview".equals(name)).toList()) {
      if (!digest.path(field).isArray()) {
        throw new IllegalStateException("DeepSeek 摘要 JSON 字段格式错误");
      }
      for (JsonNode item : digest.path(field)) {
        if (!item.has("messageId") || !ids.contains(item.path("messageId").asLong())) {
          throw new IllegalStateException("DeepSeek 摘要引用了未知邮件");
        }
      }
    }
  }

  private String systemPrompt() {
    return "输出严格 JSON，字段 overview(string)、importantItems、todoItems、riskItems、replyItems(array)。"
        + "每个数组项目必须引用输入中真实 messageId，不得推断无依据事实。";
  }

  private String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
