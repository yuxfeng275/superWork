package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.entity.EmailAccount;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.integration.WeComClient;
import com.bu.management.mapper.EmailAccountMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.EmailWeComMappingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 风险邮件即时推送（P1-4）：同步落库后异步扫描本次新邮件，
 * 命中风险信号词或高优先级处置时立即企微点对点推送，不等次日 08:00 摘要。
 * 未配置企微/未映射/发送失败一律静默降级，不影响同步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailRiskAlertService {

    private static final List<String> RISK_SIGNALS = List.of(
            "投诉", "故障", "赔付", "违约", "事故", "紧急", "严重", "延期", "法律", "律师",
            "安全", "宕机", "回滚", "资损", "逾期", "complaint", "urgent", "incident", "lawsuit");

    private final EmailMessageMapper messageMapper;
    private final EmailWeComMappingMapper mappingMapper;
    private final EmailAccountMapper accountMapper;
    private final EmailIntegrationConfigService integrationConfigService;
    private final WeComClient weComClient;

    @Async("emailTaskExecutor")
    public void scanAndPush(Long ownerUserId) {
        try {
            EmailIntegrationRuntimeConfig integration = integrationConfigService.getRuntimeConfig();
            if (!integration.isWeComConfigured()) {
                return;
            }
            EmailWeComMapping mapping = mappingMapper.selectOne(new LambdaQueryWrapper<EmailWeComMapping>()
                    .eq(EmailWeComMapping::getOwnerUserId, ownerUserId));
            if (mapping == null || !Integer.valueOf(1).equals(mapping.getEnabled())) {
                return;
            }
            // 只扫最近 30 分钟落库的邮件，避免重复推送
            List<EmailMessage> recent = messageMapper.selectList(new LambdaQueryWrapper<EmailMessage>()
                    .eq(EmailMessage::getOwnerUserId, ownerUserId)
                    .ge(EmailMessage::getCreatedAt, LocalDateTime.now().minusMinutes(30)));
            for (EmailMessage message : recent) {
                if (isRisk(message)) {
                    pushAlert(mapping.getWecomUserId(), message);
                }
            }
        } catch (Exception e) {
            log.warn("风险邮件即时推送失败: owner={}, error={}", ownerUserId, e.getMessage());
        }
    }

    boolean isRisk(EmailMessage message) {
        String subject = message.getSubject() == null ? "" : message.getSubject().toLowerCase(Locale.ROOT);
        String body = message.getBodyText() == null ? "" : message.getBodyText().toLowerCase(Locale.ROOT);
        String combined = subject + " " + body;
        return RISK_SIGNALS.stream().limit(100).anyMatch(signal -> combined.contains(signal.toLowerCase(Locale.ROOT)));
    }

    private void pushAlert(String wecomUserId, EmailMessage message) {
        try {
            EmailAccount account = accountMapper.selectById(message.getAccountId());
            String baseUrl = integrationConfigService.getRuntimeConfig().publicBaseUrl();
            String link = baseUrl == null || baseUrl.isBlank() ? ""
                    : "\n查看：" + baseUrl.replaceAll("/$", "") + "/emails";
            String content = "⚠️ 风险邮件提醒\n主题：" + (message.getSubject() == null ? "(无主题)" : message.getSubject())
                    + "\n发件人：" + message.getSenderAddress()
                    + "\n时间：" + message.getReceivedAt()
                    + "\n摘要：" + (message.getBodyPreview() == null ? "" : message.getBodyPreview())
                    + link;
            weComClient.pushText(wecomUserId, content);
        } catch (Exception e) {
            log.warn("风险邮件企微推送失败: message={}, error={}", message.getId(), e.getMessage());
        }
    }
}
