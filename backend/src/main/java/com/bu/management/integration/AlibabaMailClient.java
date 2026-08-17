package com.bu.management.integration;

import com.bu.management.config.EmailProperties;
import com.bu.management.entity.EmailAccount;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.springframework.stereotype.Component;

@Component
public class AlibabaMailClient {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final int MAX_BODY_CHARS = 200_000;
  private static final int MAX_PREVIEW_CHARS = 500;

  public record AttachmentMeta(String fileName, String contentType, long size) {}

  public record FetchedMessage(
      long uid,
      String messageId,
      String subject,
      String senderName,
      String senderAddress,
      String toAddressesJson,
      String ccAddressesJson,
      LocalDateTime receivedAt,
      String bodyText,
      String bodyPreview,
      String attachmentsJson) {}

  public record InboxSnapshot(long uidValidity, List<FetchedMessage> messages) {}

  private final EmailProperties properties;
  private final ObjectMapper objectMapper;

  public AlibabaMailClient(EmailProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public void test(String address, String password) {
    try (Store ignored = open(address, password)) {
      // A successful authenticated connection is sufficient.
    } catch (Exception exception) {
      throw new IllegalStateException(safeError(exception));
    }
  }

  public InboxSnapshot fetch(EmailAccount account, String password, Instant since, long afterUid) {
    try (Store store = open(account.getEmailAddress(), password)) {
      try (IMAPFolder folder = (IMAPFolder) store.getFolder("INBOX")) {
        folder.open(Folder.READ_ONLY);
        long uidValidity = folder.getUIDValidity();
        Message[] candidates =
            afterUid > 0
                ? folder.getMessagesByUID(afterUid + 1, UIDFolder.LASTUID)
                : folder.search(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)));
        List<FetchedMessage> messages = new ArrayList<>();
        for (Message candidate : candidates) {
          if (candidate == null) {
            continue;
          }
          Date receivedAt = candidate.getReceivedDate();
          if (afterUid == 0 && receivedAt != null && receivedAt.toInstant().isBefore(since)) {
            continue;
          }
          messages.add(convert(folder, candidate));
        }
        return new InboxSnapshot(uidValidity, messages);
      }
    } catch (Exception exception) {
      throw new IllegalStateException(safeError(exception));
    }
  }

  private Store open(String address, String password) throws MessagingException {
    Session session = Session.getInstance(mailProperties());
    Store store = session.getStore("imaps");
    store.connect(properties.getImapHost(), properties.getImapPort(), address, password);
    return store;
  }

  private Properties mailProperties() {
    Properties values = new Properties();
    values.put("mail.imaps.ssl.enable", "true");
    values.put("mail.imaps.ssl.checkserveridentity", "true");
    values.put("mail.imaps.connectiontimeout", "10000");
    values.put("mail.imaps.timeout", "30000");
    values.put("mail.imaps.writetimeout", "30000");
    values.put("mail.imaps.peek", "true");
    return values;
  }

  private FetchedMessage convert(IMAPFolder folder, Message message) throws Exception {
    Address[] from = message.getFrom();
    InternetAddress sender =
        from != null && from.length > 0 && from[0] instanceof InternetAddress address
            ? address
            : null;
    String body = limit(extractText(message), MAX_BODY_CHARS);
    String preview = limit(body.replaceAll("\s+", " ").trim(), MAX_PREVIEW_CHARS);
    Date received =
        message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
    return new FetchedMessage(
        folder.getUID(message),
        firstHeader(message, "Message-ID"),
        Optional.ofNullable(message.getSubject()).orElse("(无主题)"),
        sender == null ? null : sender.getPersonal(),
        sender == null ? firstAddress(from) : sender.getAddress(),
        addressesJson(message.getRecipients(Message.RecipientType.TO)),
        addressesJson(message.getRecipients(Message.RecipientType.CC)),
        received == null
            ? LocalDateTime.now(BUSINESS_ZONE)
            : LocalDateTime.ofInstant(received.toInstant(), BUSINESS_ZONE),
        body,
        preview,
        attachmentMetadataJson(message));
  }

  private String extractText(Part part) throws Exception {
    if (part.isMimeType("text/plain")) {
      Object content = part.getContent();
      return content == null ? "" : String.valueOf(content);
    }
    if (part.isMimeType("text/html")) {
      String html = String.valueOf(part.getContent());
      return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\1>", " ")
          .replaceAll("(?s)<[^>]+>", " ")
          .replace("&nbsp;", " ")
          .replace("&lt;", "<")
          .replace("&gt;", ">")
          .replace("&amp;", "&");
    }
    if (part.isMimeType("multipart/*")) {
      Multipart multipart = (Multipart) part.getContent();
      String htmlFallback = "";
      for (int index = 0; index < multipart.getCount(); index++) {
        Part child = multipart.getBodyPart(index);
        if (isAttachment(child)) {
          continue;
        }
        String value = extractText(child);
        if (child.isMimeType("text/plain") && !value.isBlank()) {
          return value;
        }
        if (!value.isBlank()) {
          htmlFallback = value;
        }
      }
      return htmlFallback;
    }
    return "";
  }

  private String attachmentMetadataJson(Part root) throws Exception {
    List<Map<String, Object>> attachments = new ArrayList<>();
    collectAttachments(root, attachments);
    return objectMapper.writeValueAsString(attachments);
  }

  private void collectAttachments(Part part, List<Map<String, Object>> attachments)
      throws Exception {
    if (part.isMimeType("multipart/*")) {
      Multipart multipart = (Multipart) part.getContent();
      for (int index = 0; index < multipart.getCount(); index++) {
        collectAttachments(multipart.getBodyPart(index), attachments);
      }
      return;
    }
    if (!isAttachment(part)) {
      return;
    }
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("fileName", Optional.ofNullable(part.getFileName()).orElse("attachment"));
    item.put(
        "contentType",
        Optional.ofNullable(part.getContentType()).orElse("application/octet-stream"));
    item.put("size", Math.max(0, part.getSize()));
    attachments.add(item);
  }

  private boolean isAttachment(Part part) throws MessagingException {
    return part.getFileName() != null || Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
  }

  private String addressesJson(Address[] addresses) throws JsonProcessingException {
    if (addresses == null) {
      return "[]";
    }
    return objectMapper.writeValueAsString(
        Arrays.stream(addresses).map(Address::toString).toList());
  }

  private String firstAddress(Address[] addresses) {
    return addresses == null || addresses.length == 0 ? "" : String.valueOf(addresses[0]);
  }

  private String firstHeader(Message message, String name) throws MessagingException {
    String[] values = message.getHeader(name);
    return values == null || values.length == 0 ? null : values[0];
  }

  private String limit(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value == null ? "" : value;
    }
    return value.substring(0, maxLength);
  }

  private String safeError(Exception exception) {
    if (exception instanceof AuthenticationFailedException) {
      return "邮箱认证失败，请检查邮箱地址和第三方客户端安全密码";
    }
    return "阿里云企业邮箱连接失败";
  }
}
