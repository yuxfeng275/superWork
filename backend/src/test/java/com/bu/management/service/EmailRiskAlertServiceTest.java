package com.bu.management.service;

import com.bu.management.entity.EmailMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 风险邮件信号词判定：命中/不命中/大小写。
 */
class EmailRiskAlertServiceTest {

    private final EmailRiskAlertService service = new EmailRiskAlertService(null, null, null, null, null);

    private EmailMessage message(String subject, String body) {
        EmailMessage message = new EmailMessage();
        message.setSubject(subject);
        message.setBodyText(body);
        message.setBodyPreview(body == null ? null : body.substring(0, Math.min(50, body.length())));
        return message;
    }

    @Test
    @DisplayName("标题命中投诉信号词")
    void subjectHitComplaint() {
        assertThat(service.isRisk(message("客户投诉：系统宕机", "请尽快处理"))).isTrue();
    }

    @Test
    @DisplayName("正文命中英文信号词（大小写不敏感）")
    void bodyHitEnglishSignal() {
        assertThat(service.isRisk(message("Weekly Update", "This is an URGENT incident report."))).isTrue();
    }

    @Test
    @DisplayName("普通邮件不命中")
    void normalMailNotRisk() {
        assertThat(service.isRisk(message("项目周报", "本周交付进度正常，详情见附件。"))).isFalse();
    }

    @Test
    @DisplayName("空主题空正文不命中")
    void emptyMailNotRisk() {
        assertThat(service.isRisk(message(null, null))).isFalse();
    }
}
