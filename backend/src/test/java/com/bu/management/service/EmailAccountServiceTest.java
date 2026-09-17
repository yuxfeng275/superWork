package com.bu.management.service;

import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.config.EmailProperties;
import com.bu.management.dto.EmailAccountRequest;
import com.bu.management.integration.AlibabaMailClient;
import com.bu.management.mapper.EmailAccountMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailAccountServiceTest {
    @Test
    void storesEncryptedCredentialAndNeverReturnsIt() {
        EmailAccountMapper mapper = mock(EmailAccountMapper.class);
        EmailProperties properties = new EmailProperties();
        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        EmailCredentialCipher cipher = new EmailCredentialCipher(properties);
        EmailAccountService service = new EmailAccountService(
                mapper, cipher, mock(AlibabaMailClient.class));
        EmailAccountRequest request = new EmailAccountRequest();
        request.setEmailAddress("owner@example.com");
        request.setAppPassword("app-password");
        request.setEnabled(true);
        when(mapper.selectOne(any())).thenReturn(null);

        var status = service.save(7L, request);

        verify(mapper).insert(argThat(account -> account.getOwnerUserId().equals(7L)
                && !account.getEncryptedCredential().contains("app-password")));
        assertThat(status.credentialConfigured()).isTrue();
        assertThat(status.toString()).doesNotContain("app-password").doesNotContain("v1:");
    }
}
