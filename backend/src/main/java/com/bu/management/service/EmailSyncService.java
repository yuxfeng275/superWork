package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailAccount;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.User;
import com.bu.management.integration.AlibabaMailClient;
import com.bu.management.mapper.EmailAccountMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.vo.EmailSyncStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailSyncService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Pattern ADDRESS_PATTERN =
      Pattern.compile("[^\\s@<>,;\"]+@[^\\s@<>,;\"]+");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private final EmailAccountMapper accountMapper;
  private final EmailMessageMapper messageMapper;
  private final UserMapper userMapper;
  private final EmailAccountService accountService;
  private final AlibabaMailClient mailClient;
  private final EmailProjectGroupingService groupingService;
  private final EmailRiskAlertService riskAlertService;
  private final Executor taskExecutor;

  /** 公司域名白名单（逗号分隔），空则归档模式不做域名过滤 */
  @Value("${email.company-domains:}")
  private String companyDomains;

  public EmailSyncService(
      EmailAccountMapper accountMapper,
      EmailMessageMapper messageMapper,
      UserMapper userMapper,
      EmailAccountService accountService,
      AlibabaMailClient mailClient,
      EmailProjectGroupingService groupingService,
      EmailRiskAlertService riskAlertService,
      @Qualifier("emailTaskExecutor") Executor taskExecutor) {
    this.accountMapper = accountMapper;
    this.messageMapper = messageMapper;
    this.userMapper = userMapper;
    this.accountService = accountService;
    this.mailClient = mailClient;
    this.groupingService = groupingService;
    this.riskAlertService = riskAlertService;
    this.taskExecutor = taskExecutor;
  }

  public EmailSyncStatus startAsync(Long ownerUserId) {
    EmailAccount account = accountService.requireOwned(ownerUserId);
    if (!Integer.valueOf(1).equals(account.getEnabled())) {
      throw new IllegalStateException("邮箱同步未启用");
    }
    if ("QUEUED".equals(account.getSyncStatus()) || "RUNNING".equals(account.getSyncStatus())) {
      return status(ownerUserId);
    }
    LocalDateTime queuedAt = LocalDateTime.now(BUSINESS_ZONE);
    account.setSyncStatus("QUEUED");
    account.setSyncError(null);
    account.setLastSyncStartedAt(queuedAt);
    account.setUpdatedAt(queuedAt);
    accountMapper.updateById(account);
    taskExecutor.execute(() -> syncAccount(account.getId(), ownerUserId));
    return new EmailSyncStatus(
        "QUEUED", queuedAt, account.getLastSyncCompletedAt(),
        account.getLastSyncCount() == null ? 0 : account.getLastSyncCount(), null);
  }

  public EmailSyncStatus status(Long ownerUserId) {
    EmailAccount account = accountService.requireOwned(ownerUserId);
    return new EmailSyncStatus(
        account.getSyncStatus(), account.getLastSyncStartedAt(), account.getLastSyncCompletedAt(),
        account.getLastSyncCount() == null ? 0 : account.getLastSyncCount(), account.getSyncError());
  }

  @Scheduled(cron = "${email.sync-cron:0 0 * * * *}", zone = "Asia/Shanghai")
  public void hourlySync() {
    accountMapper
        .selectEnabledAccounts()
        .forEach(
            account ->
                taskExecutor.execute(() -> syncAccount(account.getId(), account.getOwnerUserId())));
  }

  public void syncAccount(Long accountId, Long ownerUserId) {
    String token = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now();
    if (accountMapper.acquireSyncLease(accountId, ownerUserId, token, now, now.plusMinutes(30))
        != 1) {
      return;
    }
    EmailAccount account =
        accountMapper.selectOne(
            new LambdaQueryWrapper<EmailAccount>()
                .eq(EmailAccount::getId, accountId)
                .eq(EmailAccount::getOwnerUserId, ownerUserId));
    SyncContext context = buildContext(account);
    try {
      long previousUidValidity = account.getUidValidity() == null ? 0 : account.getUidValidity();
      long afterUid = account.getLastUid() == null ? 0 : account.getLastUid();
      Instant since = account.getInitialSyncFrom() == null
          ? LocalDate.now(BUSINESS_ZONE).minusDays(6).atStartOfDay(BUSINESS_ZONE).toInstant()
          : account.getInitialSyncFrom().atZone(BUSINESS_ZONE).toInstant();
      AlibabaMailClient.InboxSnapshot snapshot =
          mailClient.fetch(account, accountService.credential(account), since, afterUid);
      if (previousUidValidity != 0 && previousUidValidity != snapshot.uidValidity()) {
        snapshot = mailClient.fetch(account, accountService.credential(account), since, 0);
      }
      long maxUid = previousUidValidity == snapshot.uidValidity() ? afterUid : 0;
      for (AlibabaMailClient.FetchedMessage fetched : snapshot.messages()) {
        insertMessage(account, snapshot.uidValidity(), fetched, context);
        maxUid = Math.max(maxUid, fetched.uid());
      }
      account.setUidValidity(snapshot.uidValidity());
      account.setLastUid(maxUid);
      account.setSyncStatus("SUCCESS");
      account.setSyncError(null);
      account.setLastSyncCount(snapshot.messages().size());
      account.setLastSyncCompletedAt(LocalDateTime.now());
      if (context.archive()) {
        log.info(
            "归档邮箱同步完成 account {} owner {}：归属匹配 {} 封，未匹配收件人 {} 封，域名不符跳过 {} 封，重复跳过 {} 封",
            accountId,
            ownerUserId,
            context.matchedCount,
            context.unmatchedCount,
            context.domainSkippedCount,
            context.duplicateSkippedCount);
      }
      if (!snapshot.messages().isEmpty()) {
        groupingService.startAsync(ownerUserId, false);
        // 归档模式：匹配出的每个真实 owner 也各跑一次归属（已有 RUNNING 任务由 startAsync 自行幂等）
        context.matchedOwnerIds.stream()
            .filter(matchedOwnerId -> !matchedOwnerId.equals(ownerUserId))
            .forEach(matchedOwnerId -> groupingService.startAsync(matchedOwnerId, false));
        riskAlertService.scanAndPush(ownerUserId);
      }
    } catch (Exception exception) {
      account.setSyncStatus("FAILED");
      account.setSyncError(limit(exception.getMessage(), 500));
      log.warn(
          "Email sync failed for account {} owner {}: {}",
          accountId,
          ownerUserId,
          safeMessage(exception));
    } finally {
      account.setLockToken(null);
      account.setLockUntil(null);
      account.setUpdatedAt(LocalDateTime.now());
      accountMapper.updateById(account);
    }
  }

  /** 构建同步上下文：个人模式为空上下文；归档模式预载收件人→用户映射与公司域名白名单 */
  private SyncContext buildContext(EmailAccount account) {
    if (!"ARCHIVE".equalsIgnoreCase(account.getMode())) {
      return SyncContext.personal();
    }
    Map<String, Long> emailToOwner = new HashMap<>();
    userMapper
        .selectList(new LambdaQueryWrapper<User>().isNotNull(User::getEmail))
        .forEach(
            user -> {
              String email = normalizeAddress(user.getEmail());
              if (!email.isEmpty()) {
                emailToOwner.putIfAbsent(email, user.getId());
              }
            });
    Set<String> domains = new LinkedHashSet<>();
    if (companyDomains != null) {
      for (String domain : companyDomains.split(",")) {
        String value = domain.trim().toLowerCase(Locale.ROOT);
        if (!value.isEmpty()) {
          domains.add(value);
        }
      }
    }
    return new SyncContext(true, emailToOwner, domains);
  }

  private void insertMessage(
      EmailAccount account,
      long uidValidity,
      AlibabaMailClient.FetchedMessage fetched,
      SyncContext context) {
    List<String> recipients = List.of();
    if (context.archive()) {
      recipients = recipientsOf(fetched);
      // 归档邮箱只保留与公司域名相关的邮件（发件人或任一收件人属于公司域名）
      if (!context.companyDomains().isEmpty()
          && !inCompanyDomain(fetched.senderAddress(), context.companyDomains())
          && recipients.stream()
              .noneMatch(address -> inCompanyDomain(address, context.companyDomains()))) {
        context.domainSkippedCount++;
        return;
      }
      if (duplicated(fetched)) {
        context.duplicateSkippedCount++;
        return;
      }
    }
    EmailMessage message = new EmailMessage();
    message.setOwnerUserId(account.getOwnerUserId());
    message.setAccountId(account.getId());
    message.setFolder("INBOX");
    message.setUidValidity(uidValidity);
    message.setUid(fetched.uid());
    message.setInternetMessageId(fetched.messageId());
    message.setSubject(fetched.subject());
    message.setSenderName(fetched.senderName());
    message.setSenderAddress(fetched.senderAddress());
    message.setToAddressesJson(fetched.toAddressesJson());
    message.setCcAddressesJson(fetched.ccAddressesJson());
    message.setReceivedAt(fetched.receivedAt());
    message.setBodyText(fetched.bodyText());
    message.setBodyPreview(fetched.bodyPreview());
    message.setAttachmentsJson(fetched.attachmentsJson());
    message.setCreatedAt(LocalDateTime.now());
    if (context.archive()) {
      Long matched = matchOwner(recipients, context.emailToOwner());
      if (matched == null) {
        context.unmatchedCount++;
        message.setGroupingReason("[归档未匹配收件人]");
      } else {
        message.setOwnerUserId(matched);
        context.matchedOwnerIds.add(matched);
        context.matchedCount++;
      }
    }
    try {
      messageMapper.insert(message);
    } catch (DuplicateKeyException ignored) {
      log.debug(
          "Duplicate IMAP message skipped for account {}, UIDVALIDITY {}, UID {}",
          account.getId(),
          uidValidity,
          fetched.uid());
    }
  }

  /** 归档模式：同一互联网邮件 ID 已入库则跳过（同一封邮件会被多个账号各收一份） */
  private boolean duplicated(AlibabaMailClient.FetchedMessage fetched) {
    String messageId = fetched.messageId();
    if (messageId == null || messageId.isBlank()) {
      return false;
    }
    return messageMapper.selectCount(
            new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getInternetMessageId, messageId.trim()))
        > 0;
  }

  /** 收件人集合：to + cc，已归一化为纯小写地址 */
  private List<String> recipientsOf(AlibabaMailClient.FetchedMessage fetched) {
    List<String> addresses = new ArrayList<>();
    addresses.addAll(parseAddresses(fetched.toAddressesJson()));
    addresses.addAll(parseAddresses(fetched.ccAddressesJson()));
    return addresses;
  }

  /** 解析地址 JSON：优先 Jackson 反序列化 List&lt;String&gt;，失败则正则兜底 */
  private List<String> parseAddresses(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      List<String> values = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
      List<String> addresses = new ArrayList<>();
      for (String value : values) {
        String address = normalizeAddress(value);
        if (!address.isEmpty()) {
          addresses.add(address);
        }
      }
      return addresses;
    } catch (Exception ignored) {
      List<String> addresses = new ArrayList<>();
      Matcher matcher = ADDRESS_PATTERN.matcher(json);
      while (matcher.find()) {
        addresses.add(matcher.group().toLowerCase(Locale.ROOT));
      }
      return addresses;
    }
  }

  /** 归一化地址：从 "张三 <zhangsan+xm@corp.com>" 取纯地址并小写 */
  private String normalizeAddress(String value) {
    if (value == null) {
      return "";
    }
    Matcher matcher = ADDRESS_PATTERN.matcher(value);
    return matcher.find() ? matcher.group().trim().toLowerCase(Locale.ROOT) : "";
  }

  private boolean inCompanyDomain(String address, Set<String> domains) {
    String normalized = normalizeAddress(address);
    int at = normalized.lastIndexOf('@');
    return at > 0 && domains.contains(normalized.substring(at + 1));
  }

  /** 收件人→真实 owner：按 to 顺序取第一个命中的用户 */
  private Long matchOwner(List<String> recipients, Map<String, Long> emailToOwner) {
    for (String recipient : recipients) {
      Long ownerId = emailToOwner.get(recipient);
      if (ownerId != null) {
        return ownerId;
      }
    }
    return null;
  }

  /** 归档模式运行时上下文：收件人→用户映射、公司域名白名单与本次统计。 */
  private static final class SyncContext {
    private final boolean archiveMode;
    private final Map<String, Long> emailToOwner;
    private final Set<String> companyDomains;
    private final Set<Long> matchedOwnerIds = new HashSet<>();
    private int matchedCount;
    private int unmatchedCount;
    private int domainSkippedCount;
    private int duplicateSkippedCount;

    private SyncContext(
        boolean archiveMode, Map<String, Long> emailToOwner, Set<String> companyDomains) {
      this.archiveMode = archiveMode;
      this.emailToOwner = emailToOwner;
      this.companyDomains = companyDomains;
    }

    private static SyncContext personal() {
      return new SyncContext(false, Map.of(), Set.of());
    }

    private boolean archive() {
      return archiveMode;
    }

    private Map<String, Long> emailToOwner() {
      return emailToOwner;
    }

    private Set<String> companyDomains() {
      return companyDomains;
    }
  }

  private String limit(String value, int maxLength) {
    if (value == null) {
      return "邮箱同步失败";
    }
    return value.substring(0, Math.min(maxLength, value.length()));
  }

  private String safeMessage(Exception exception) {
    return exception.getMessage() == null
        ? exception.getClass().getSimpleName()
        : limit(exception.getMessage(), 200);
  }
}
