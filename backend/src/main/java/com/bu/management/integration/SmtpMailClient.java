package com.bu.management.integration;

import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.config.EmailProperties;
import com.bu.management.entity.EmailAccount;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 复用 IMAP 绑定的账号与 AES-256-GCM 加密的安全密码；仅用于回复已同步邮件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpMailClient {

    private final EmailProperties properties;
    private final EmailCredentialCipher cipher;

    /** 用绑定邮箱向 toAddress 发送纯文本邮件。返回实际发件人地址。 */
    public String send(EmailAccount account, String toAddress, String subject, String bodyText) {
        String password = cipher.decrypt(account.getEncryptedCredential());
        String from = account.getEmailAddress();
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost());
        props.put("mail.smtp.port", String.valueOf(properties.getSmtpPort() == 0 ? 465 : properties.getSmtpPort()));
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "30000");
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(from, password);
            }
        });
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress, false));
            message.setSubject(subject, "UTF-8");
            message.setText(bodyText, "UTF-8");
            message.setSentDate(new java.util.Date());
            try (Transport transport = session.getTransport("smtp")) {
                transport.connect(from, password);
                transport.sendMessage(message, message.getAllRecipients());
            }
            return from;
        } catch (MessagingException e) {
            log.warn("SMTP 发送失败: from={}, to={}, error={}", from, toAddress, e.getMessage());
            throw new IllegalStateException(safeError(e), e);
        }
    }

    private String smtpHost() {
        // 阿里云企业邮箱固定 SMTP 集群；保留 properties 扩展位
        return "smtp.qiye.aliyun.com";
    }

    private String safeError(MessagingException e) {
        String message = e.getMessage() == null ? "SMTP 发送失败" : e.getMessage();
        // 防止服务器横幅带出凭据片段
        return message.replaceAll("(?i)(password|auth)[^;\\n]{0,80}", "$1=***");
    }
}
