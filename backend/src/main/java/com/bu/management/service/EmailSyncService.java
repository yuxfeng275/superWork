package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailAccount;
import com.bu.management.entity.EmailMessage;
import com.bu.management.integration.AlibabaMailClient;
import com.bu.management.mapper.EmailAccountMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.vo.EmailSyncStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailSyncService {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private final EmailAccountMapper accountMapper;
  private final EmailMessageMapper messageMapper;
  private final EmailAccountService accountService;
  private final AlibabaMailClient mailClient;
  private final Executor taskExecutor;

  public EmailSyncService(
      EmailAccountMapper accountMapper,
      EmailMessageMapper messageMapper,
      EmailAccountService accountService,
      AlibabaMailClient mailClient,
      @Qualifier("emailTaskExecutor") Executor taskExecutor) {
    this.accountMapper = accountMapper;
    this.messageMapper = messageMapper;
    this.accountService = accountService;
    this.mailClient = mailClient;
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
        insertMessage(account, snapshot.uidValidity(), fetched);
        maxUid = Math.max(maxUid, fetched.uid());
      }
      account.setUidValidity(snapshot.uidValidity());
      account.setLastUid(maxUid);
      account.setSyncStatus("SUCCESS");
      account.setSyncError(null);
      account.setLastSyncCount(snapshot.messages().size());
      account.setLastSyncCompletedAt(LocalDateTime.now());
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

  private void insertMessage(
      EmailAccount account, long uidValidity, AlibabaMailClient.FetchedMessage fetched) {
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
