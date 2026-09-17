package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailAccount;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.EmailSentReply;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.integration.SmtpMailClient;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.EmailSentReplyMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * SMTP 回复发送：AI 生成的草稿经用户确认后，用绑定企业邮箱直接回复原发件人。
 * 发送记录落 email_sent_reply；失败落 FAILED + 脱敏错误，前端可重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailReplyService {

    private final EmailMessageMapper messageMapper;
    private final EmailSentReplyMapper replyMapper;
    private final EmailAccountService accountService;
    private final SmtpMailClient smtpMailClient;

    public record ReplyResult(Long replyId, String status, String errorMessage) {}

    public ReplyResult send(Long ownerUserId, Long messageId, String subject, String bodyText) {
        EmailMessage message = messageMapper.selectOne(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getId, messageId)
                .eq(EmailMessage::getOwnerUserId, ownerUserId));
        if (message == null) {
            throw new ResourceNotFoundException("邮件不存在");
        }
        String toAddress = message.getSenderAddress();
        if (toAddress == null || toAddress.isBlank()) {
            throw new IllegalStateException("原邮件缺少发件人地址，无法回复");
        }
        EmailAccount account = accountService.requireOwned(ownerUserId);
        EmailSentReply record = new EmailSentReply();
        record.setOwnerUserId(ownerUserId);
        record.setMessageId(messageId);
        record.setToAddress(toAddress);
        record.setSubject(subject == null || subject.isBlank()
                ? defaultSubject(message.getSubject()) : subject);
        record.setBodyText(bodyText == null || bodyText.isBlank()
                ? "" : bodyText);
        record.setStatus("FAILED");
        record.setSentAt(LocalDateTime.now());
        try {
            smtpMailClient.send(account, toAddress, record.getSubject(), record.getBodyText());
            record.setStatus("SENT");
            record.setErrorMessage(null);
        } catch (Exception e) {
            record.setErrorMessage(sanitize(e.getMessage()));
            persist(record);
            return new ReplyResult(record.getId(), "FAILED", record.getErrorMessage());
        }
        persist(record);
        return new ReplyResult(record.getId(), "SENT", null);
    }

    /** 某邮件的往来发送记录（线程视图）。 */
    public List<EmailSentReply> thread(Long ownerUserId, Long messageId) {
        return replyMapper.selectList(new LambdaQueryWrapper<EmailSentReply>()
                .eq(EmailSentReply::getOwnerUserId, ownerUserId)
                .eq(EmailSentReply::getMessageId, messageId)
                .orderByDesc(EmailSentReply::getId));
    }

    private void persist(EmailSentReply record) {
        try {
            replyMapper.insert(record);
        } catch (Exception e) {
            log.warn("回复发送记录落库失败: {}", e.getMessage());
        }
    }

    private String defaultSubject(String original) {
        String base = original == null || original.isBlank() ? "(无主题)" : original;
        return base.matches("(?i)^\\s*(re|回复)[:：]?.*") ? base : "回复：" + base;
    }

    private String sanitize(String message) {
        if (message == null) return "发送失败";
        String cleaned = message.replaceAll("(?i)(password|auth|credential)[^;\\n]{0,80}", "$1=***");
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }
}
