package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.dto.EmailAccountRequest;
import com.bu.management.entity.EmailAccount;
import com.bu.management.integration.AlibabaMailClient;
import com.bu.management.mapper.EmailAccountMapper;
import com.bu.management.vo.EmailAccountStatus;
import com.bu.management.vo.EmailConnectionTestResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class EmailAccountService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PROVIDER = "ALIBABA_CLOUD_ENTERPRISE_MAIL";

    private final EmailAccountMapper mapper;
    private final EmailCredentialCipher cipher;
    private final AlibabaMailClient client;

    public EmailAccount findOwned(Long ownerUserId) {
        return mapper.selectOne(new LambdaQueryWrapper<EmailAccount>()
                .eq(EmailAccount::getOwnerUserId, ownerUserId));
    }

    public EmailAccount requireOwned(Long ownerUserId) {
        EmailAccount account = findOwned(ownerUserId);
        if (account == null) {
            throw new IllegalStateException("尚未绑定邮箱");
        }
        return account;
    }

    public EmailAccountStatus getStatus(Long ownerUserId) {
        EmailAccount account = findOwned(ownerUserId);
        return account == null ? unconfiguredStatus() : toStatus(account);
    }

    @Transactional
    public EmailAccountStatus save(Long ownerUserId, EmailAccountRequest request) {
        EmailAccount account = findOwned(ownerUserId);
        boolean insert = account == null;
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (insert) {
            account = new EmailAccount();
            account.setOwnerUserId(ownerUserId);
            account.setCreatedAt(now);
            account.setSyncStatus("IDLE");
            account.setLastSyncCount(0);
            account.setConnectionStatus("UNTESTED");
            account.setInitialSyncFrom(LocalDate.now(BUSINESS_ZONE).minusDays(6).atStartOfDay());
        }
        String newAddress = request.getEmailAddress().trim().toLowerCase();
        boolean changedAddress = !insert && !newAddress.equals(account.getEmailAddress());
        if (changedAddress) {
            throw new IllegalStateException("更换邮箱地址前请先解绑当前账户");
        }
        account.setEmailAddress(newAddress);
        if (StringUtils.hasText(request.getAppPassword())) {
            account.setEncryptedCredential(cipher.encrypt(request.getAppPassword().trim()));
            account.setConnectionStatus("UNTESTED");
            account.setConnectionMessage(null);
        }
        if (!StringUtils.hasText(account.getEncryptedCredential())) {
            throw new IllegalStateException("请提供第三方客户端安全密码");
        }
        account.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? 0 : 1);
        account.setUpdatedAt(now);
        if (insert) {
            mapper.insert(account);
        } else {
            mapper.updateById(account);
        }
        return toStatus(account);
    }

    @Transactional
    public EmailConnectionTestResponse test(Long ownerUserId) {
        EmailAccount account = requireOwned(ownerUserId);
        LocalDateTime testedAt = LocalDateTime.now(BUSINESS_ZONE);
        try {
            client.test(account.getEmailAddress(), credential(account));
            account.setConnectionStatus("CONNECTED");
            account.setConnectionMessage("连接成功");
            account.setLastTestedAt(testedAt);
            account.setUpdatedAt(testedAt);
            mapper.updateById(account);
            return new EmailConnectionTestResponse(true, "连接成功", testedAt);
        } catch (RuntimeException exception) {
            String message = sanitize(exception.getMessage());
            account.setConnectionStatus("FAILED");
            account.setConnectionMessage(message);
            account.setLastTestedAt(testedAt);
            account.setUpdatedAt(testedAt);
            mapper.updateById(account);
            return new EmailConnectionTestResponse(false, message, testedAt);
        }
    }

    @Transactional
    public void delete(Long ownerUserId) {
        EmailAccount account = requireOwned(ownerUserId);
        // ON DELETE CASCADE removes messages. Credential disappears with the row.
        mapper.delete(new LambdaQueryWrapper<EmailAccount>()
                .eq(EmailAccount::getId, account.getId())
                .eq(EmailAccount::getOwnerUserId, ownerUserId));
    }

    public String credential(EmailAccount account) {
        return cipher.decrypt(account.getEncryptedCredential());
    }

    private EmailAccountStatus unconfiguredStatus() {
        return new EmailAccountStatus(false, false, PROVIDER, null, false,
                "UNCONFIGURED", null, null, null, "IDLE", null);
    }

    private EmailAccountStatus toStatus(EmailAccount account) {
        return new EmailAccountStatus(
                true,
                Integer.valueOf(1).equals(account.getEnabled()),
                PROVIDER,
                account.getEmailAddress(),
                StringUtils.hasText(account.getEncryptedCredential()),
                account.getConnectionStatus(),
                account.getConnectionMessage(),
                account.getLastTestedAt(),
                account.getLastSyncCompletedAt(),
                account.getSyncStatus(),
                account.getSyncError());
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "邮箱连接失败";
        }
        return value.substring(0, Math.min(500, value.length()));
    }
}
