package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bu.management.entity.EmailMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEmailDigestGeneratorTest {
  @Test
  void createsTraceableDeterministicFallback() {
    EmailMessage message = new EmailMessage();
    message.setId(42L);
    message.setSubject("生产故障，请立即处理");
    message.setSenderName("张三");
    message.setSenderAddress("zhang@example.com");
    message.setReceivedAt(LocalDateTime.of(2026, 8, 16, 10, 0));
    message.setBodyText("服务异常，有风险，需要今天回复。");
    DigestContent result = new RuleEmailDigestGenerator().generate(List.of(message));
    assertThat(result.overview()).contains("1");
    assertThat(result.importantItems()).contains("42").contains("生产故障");
    assertThat(result.riskItems()).contains("42");
    assertThat(result.replyItems()).contains("42");
    assertThat(result.fallback()).isTrue();
  }

  @Test
  void producesExplicitEmptyState() {
    DigestContent result = new RuleEmailDigestGenerator().generate(List.of());
    assertThat(result.overview()).contains("无邮件");
    assertThat(result.importantItems()).isEqualTo("[]");
  }
}
