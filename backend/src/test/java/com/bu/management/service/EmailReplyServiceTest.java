package com.bu.management.service;

import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.EmailSentReply;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.EmailSentReplyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SMTP 回复服务：归属校验、默认主题、失败落库脱敏。
 */
@ExtendWith(MockitoExtension.class)
class EmailReplyServiceTest {

    @Mock
    private EmailMessageMapper messageMapper;
    @Mock
    private EmailSentReplyMapper replyMapper;
    @Mock
    private EmailAccountService accountService;
    @Mock
    private com.bu.management.integration.SmtpMailClient smtpMailClient;

    private EmailReplyService service;

    @BeforeEach
    void setUp() {
        service = new EmailReplyService(messageMapper, replyMapper, accountService, smtpMailClient);
    }

    private EmailMessage message(Long id, Long owner, String subject, String sender) {
        EmailMessage message = new EmailMessage();
        message.setId(id);
        message.setOwnerUserId(owner);
        message.setSubject(subject);
        message.setSenderAddress(sender);
        return message;
    }

    @Test
    @DisplayName("他人邮件回复被拒绝")
    void replyRejectsForeignMessage() {
        when(messageMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.send(7L, 11L, null, "内容"))
                .isInstanceOf(com.bu.management.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("发送成功落 SENT 记录，主题自动加回复前缀")
    void replySuccessPersistsSentRecord() {
        when(messageMapper.selectOne(any()))
                .thenReturn(message(11L, 7L, "项目排期确认", "client@example.com"));
        com.bu.management.entity.EmailAccount account = new com.bu.management.entity.EmailAccount();
        account.setEmailAddress("me@company.com");
        when(accountService.requireOwned(7L)).thenReturn(account);

        var result = service.send(7L, 11L, null, "收到，我们今天回复。");

        assertThat(result.status()).isEqualTo("SENT");
        ArgumentCaptor<EmailSentReply> captor = ArgumentCaptor.forClass(EmailSentReply.class);
        verify(replyMapper).insert(captor.capture());
        EmailSentReply saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("SENT");
        assertThat(saved.getSubject()).startsWith("回复：");
        assertThat(saved.getToAddress()).isEqualTo("client@example.com");
    }

    @Test
    @DisplayName("SMTP 失败落 FAILED 记录并脱敏错误")
    void replyFailurePersistsFailedRecord() {
        when(messageMapper.selectOne(any()))
                .thenReturn(message(11L, 7L, "项目排期确认", "client@example.com"));
        com.bu.management.entity.EmailAccount account = new com.bu.management.entity.EmailAccount();
        account.setEmailAddress("me@company.com");
        when(accountService.requireOwned(7L)).thenReturn(account);
        when(smtpMailClient.send(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("535 auth failed password=secret123"));

        var result = service.send(7L, 11L, "Re: 项目排期确认", "内容");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).doesNotContain("secret123");
        ArgumentCaptor<EmailSentReply> captor = ArgumentCaptor.forClass(EmailSentReply.class);
        verify(replyMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("thread 只查本人该邮件的发送记录")
    void threadFiltersOwnerAndMessage() {
        when(replyMapper.selectList(any())).thenReturn(List.of(new EmailSentReply()));

        assertThat(service.thread(7L, 11L)).hasSize(1);
    }
}
