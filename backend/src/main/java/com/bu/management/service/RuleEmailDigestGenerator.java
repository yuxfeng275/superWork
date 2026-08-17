package com.bu.management.service;

import com.bu.management.entity.EmailMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

@Component
public class RuleEmailDigestGenerator {
  private static final List<String> IMPORTANT =
      List.of("重要", "紧急", "故障", "立即", "urgent", "important");
  private static final List<String> TODO = List.of("请", "待办", "处理", "完成", "提交", "todo", "action");
  private static final List<String> RISK = List.of("风险", "异常", "故障", "延期", "投诉", "risk", "error");
  private static final List<String> REPLY = List.of("回复", "确认", "反馈", "答复", "reply");
  private final ObjectMapper objectMapper = new ObjectMapper();

  public DigestContent generate(List<EmailMessage> messages) {
    if (messages.isEmpty()) {
      return new DigestContent("昨日无邮件。", "[]", "[]", "[]", "[]", true);
    }
    return new DigestContent(
        "昨日共收到 " + messages.size() + " 封邮件；当前展示规则摘要。",
        items(messages, message -> matches(message, IMPORTANT)),
        items(messages, message -> matches(message, TODO)),
        items(messages, message -> matches(message, RISK)),
        items(messages, message -> matches(message, REPLY)),
        true);
  }

  private boolean matches(EmailMessage message, List<String> keywords) {
    String value =
        ((message.getSubject() == null ? "" : message.getSubject())
                + " "
                + (message.getBodyText() == null ? "" : message.getBodyText()))
            .toLowerCase(Locale.ROOT);
    return keywords.stream().anyMatch(value::contains);
  }

  private String items(List<EmailMessage> messages, Predicate<EmailMessage> predicate) {
    List<Map<String, Object>> result = new ArrayList<>();
    messages.stream()
        .filter(predicate)
        .limit(20)
        .forEach(
            message -> {
              Map<String, Object> item = new LinkedHashMap<>();
              item.put("messageId", message.getId());
              item.put("subject", message.getSubject());
              item.put(
                  "sender",
                  message.getSenderName() == null
                      ? message.getSenderAddress()
                      : message.getSenderName());
              result.add(item);
            });
    try {
      return objectMapper.writeValueAsString(result);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("规则摘要序列化失败", exception);
    }
  }
}
