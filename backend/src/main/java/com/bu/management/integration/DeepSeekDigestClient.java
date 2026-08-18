package com.bu.management.integration;

import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.service.EmailIntegrationConfigService;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.Project;
import com.bu.management.service.EmailProjectAssignment;
import com.bu.management.service.DigestContent;
import com.bu.management.service.EmailInterpretationContent;
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
          objectMapper.writeValueAsString(digest.path("topics")),
          objectMapper.writeValueAsString(digest.path("progressItems")),
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

  public String configuredModel() {
    return configService.getRuntimeConfig().deepSeekModel();
  }

  public EmailInterpretationContent interpret(EmailMessage message, Long ownerUserId) {
    if (!ownerUserId.equals(message.getOwnerUserId())) {
      throw new IllegalStateException("邮件不属于当前用户");
    }
    EmailIntegrationRuntimeConfig config = configService.getRuntimeConfig();
    if (!config.isDeepSeekConfigured()) {
      throw new IllegalStateException("DeepSeek 未配置或未启用");
    }
    try {
      Map<String, Object> mail = new LinkedHashMap<>();
      mail.put("messageId", message.getId());
      mail.put("subject", message.getSubject() == null ? "" : message.getSubject());
      mail.put("senderName", message.getSenderName() == null ? "" : message.getSenderName());
      mail.put("senderAddress", message.getSenderAddress() == null ? "" : message.getSenderAddress());
      mail.put("receivedAt", String.valueOf(message.getReceivedAt()));
      String body = message.getBodyText() == null ? "" : message.getBodyText();
      mail.put("bodyText", body.substring(0, Math.min(body.length(), 100_000)));
      JsonNode result = callJson(config, interpretationPrompt(), objectMapper.writeValueAsString(mail));
      validateInterpretation(result);
      return new EmailInterpretationContent(
          result.path("summary").asText(),
          result.path("senderIntent").asText(),
          objectMapper.writeValueAsString(result.path("keyPoints")),
          objectMapper.writeValueAsString(result.path("actionItems")),
          objectMapper.writeValueAsString(result.path("risks")),
          result.path("replySuggestion").asText());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AI 解读被中断", exception);
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException stateException) throw stateException;
      throw new IllegalStateException("AI 解读响应无效", exception);
    }
  }

  public List<EmailProjectAssignment> groupByProjects(
      List<EmailMessage> messages, List<Project> projects, Long ownerUserId) {
    EmailIntegrationRuntimeConfig config = configService.getRuntimeConfig();
    if (!config.isDeepSeekConfigured()) {
      throw new IllegalStateException("DeepSeek 未配置或未启用");
    }
    try {
      List<Map<String, Object>> candidates = projects.stream().map(project -> {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("projectId", project.getId());
        value.put("name", project.getName());
        value.put("fullPath", project.getFullPath());
        value.put("code", project.getCode() == null ? "" : project.getCode());
        return value;
      }).toList();
      List<Map<String, Object>> mails = new ArrayList<>();
      for (EmailMessage message : messages) {
        if (!ownerUserId.equals(message.getOwnerUserId())) {
          throw new IllegalStateException("分组输入包含其他用户邮件");
        }
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("messageId", message.getId());
        mail.put("subject", message.getSubject() == null ? "" : message.getSubject());
        mail.put("sender", message.getSenderAddress() == null ? "" : message.getSenderAddress());
        String body = message.getBodyText() == null ? "" : message.getBodyText();
        mail.put("content", body.substring(0, Math.min(body.length(), 4_000)));
        mails.add(mail);
      }
      JsonNode result = callJson(config, groupingPrompt(), objectMapper.writeValueAsString(Map.of(
          "projects", candidates, "emails", mails)));
      JsonNode assignments = result.path("assignments");
      if (!assignments.isArray()) throw new IllegalStateException("智能分组响应格式错误");
      List<EmailProjectAssignment> output = new ArrayList<>();
      for (JsonNode assignment : assignments) {
        Long projectId = assignment.path("projectId").isNull()
            || assignment.path("projectId").isMissingNode()
            ? null : assignment.path("projectId").asLong();
        output.add(new EmailProjectAssignment(
            assignment.path("messageId").asLong(), projectId,
            assignment.path("confidence").asDouble(0),
            assignment.path("reason").asText("无法判断所属项目")));
      }
      return output;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("智能分组被中断", exception);
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException stateException) throw stateException;
      throw new IllegalStateException("智能分组响应无效", exception);
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

  private JsonNode callJson(EmailIntegrationRuntimeConfig config, String prompt, String input)
      throws Exception {
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("model", config.deepSeekModel());
    requestBody.put("response_format", Map.of("type", "json_object"));
    requestBody.put("temperature", 0.1);
    requestBody.put("messages", List.of(
        Map.of("role", "system", "content", prompt),
        Map.of("role", "user", "content", input)));
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(trimSlash(config.deepSeekBaseUrl()) + "/chat/completions"))
        .timeout(Duration.ofSeconds(45))
        .header("Authorization", "Bearer " + config.deepSeekApiKey())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException("DeepSeek 调用失败(" + response.statusCode() + ")");
    }
    String content = objectMapper.readTree(response.body())
        .path("choices").path(0).path("message").path("content").asText(null);
    if (content == null) throw new IllegalStateException("DeepSeek 返回缺少内容");
    return objectMapper.readTree(content);
  }

  private void validateInterpretation(JsonNode result) {
    if (!result.isObject()
        || !result.path("summary").isTextual()
        || !result.path("senderIntent").isTextual()
        || !result.path("keyPoints").isArray()
        || !result.path("actionItems").isArray()
        || !result.path("risks").isArray()
        || !result.path("replySuggestion").isTextual()) {
      throw new IllegalStateException("AI 解读 JSON 字段格式错误");
    }
  }

  private String groupingPrompt() {
    return "你是企业邮件项目归档助手。根据邮件标题、正文、发件人以及项目名称、编码、完整路径，"
        + "将每封邮件分配到最匹配的一个真实项目。输出严格 JSON："
        + "{assignments:[{messageId,projectId,confidence,reason}]}。"
        + "projectId只能取候选项目ID；若无法可靠判断，projectId必须为null。"
        + "confidence为0到1，低于0.65时必须使用null。不得臆造项目。";
  }

  private String interpretationPrompt() {
    return "你是企业邮件分析助手。请忠于原文，不得臆造。输出严格 JSON："
        + "summary(string，核心结论)、senderIntent(string，发件人意图)、"
        + "keyPoints(string[])、actionItems([{content,deadline,priority}])、"
        + "risks(string[])、replySuggestion(string，建议回复草稿)。";
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
    if (!digest.path("topics").isArray() || !digest.path("progressItems").isArray()) {
      throw new IllegalStateException("DeepSeek 纪要 JSON 缺少议题或进展字段");
    }
    Set<Long> ids =
        messages.stream().map(EmailMessage::getId).collect(java.util.stream.Collectors.toSet());
    for (String field : List.of("topics", "progressItems")) {
      for (JsonNode item : digest.path(field)) {
        if (!item.path("messageIds").isArray()) {
          throw new IllegalStateException("DeepSeek 纪要缺少邮件引用");
        }
        for (JsonNode id : item.path("messageIds")) {
          if (!ids.contains(id.asLong())) throw new IllegalStateException("DeepSeek 纪要引用了未知邮件");
        }
      }
    }
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
    return "你是企业邮件AI纪要助手，请参照钉钉AI会议纪要的结构，跨邮件去重、合并同一议题并忠于原文。"
        + "输出严格JSON：overview(string，3句话内的高层总结)，"
        + "topics([{title,summary,status,messageIds}])，status只能是已完成/推进中/待确认；"
        + "progressItems([{title,status,detail,messageIds}])，"
        + "importantItems、todoItems、riskItems、replyItems(array)。"
        + "important/todo/risk/reply每项必须含真实messageId、title、content；todo尽量给出sender、deadline、action。"
        + "topics/progressItems每项必须含真实messageIds数组。按业务影响排序，不得臆造。";
  }

  private String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
