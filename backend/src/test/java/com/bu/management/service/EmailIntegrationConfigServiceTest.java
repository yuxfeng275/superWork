package com.bu.management.service;

import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.config.EmailProperties;
import com.bu.management.dto.EmailIntegrationConfigRequest;
import com.bu.management.entity.EmailIntegrationConfig;
import com.bu.management.mapper.EmailIntegrationConfigMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailIntegrationConfigServiceTest {
    @Test
    void encryptsPageSecretsAndNeverReturnsThem() {
        EmailIntegrationConfigMapper mapper = mock(EmailIntegrationConfigMapper.class);
        AtomicReference<EmailIntegrationConfig> stored = new AtomicReference<>();
        when(mapper.selectById(1L)).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(mapper).insert(any(EmailIntegrationConfig.class));
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(mapper).updateById(any(EmailIntegrationConfig.class));
        EmailProperties properties = new EmailProperties();
        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        EmailIntegrationConfigService service = new EmailIntegrationConfigService(
                mapper, new EmailCredentialCipher(properties));

        EmailIntegrationConfigRequest request = request("deepseek-secret", "wecom-secret");
        var status = service.save(request, 7L);

        assertThat(stored.get().getEncryptedDeepSeekApiKey()).startsWith("v1:")
                .doesNotContain("deepseek-secret");
        assertThat(stored.get().getEncryptedWeComSecret()).startsWith("v1:")
                .doesNotContain("wecom-secret");
        assertThat(status.deepSeekApiKeyConfigured()).isTrue();
        assertThat(status.weComSecretConfigured()).isTrue();
        assertThat(status.toString()).doesNotContain("deepseek-secret").doesNotContain("wecom-secret")
                .doesNotContain("v1:");
        EmailIntegrationRuntimeConfig runtime = service.getRuntimeConfig();
        assertThat(runtime.deepSeekApiKey()).isEqualTo("deepseek-secret");
        assertThat(runtime.weComSecret()).isEqualTo("wecom-secret");
    }

    @Test
    void blankSecretFieldsPreserveExistingEncryptedValues() {
        EmailIntegrationConfigMapper mapper = mock(EmailIntegrationConfigMapper.class);
        EmailProperties properties = new EmailProperties();
        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        EmailCredentialCipher cipher = new EmailCredentialCipher(properties);
        EmailIntegrationConfig existing = new EmailIntegrationConfig();
        existing.setId(1L);
        existing.setEncryptedDeepSeekApiKey(cipher.encrypt("old-deepseek"));
        existing.setEncryptedWeComSecret(cipher.encrypt("old-wecom"));
        when(mapper.selectById(1L)).thenReturn(existing);
        EmailIntegrationConfigService service = new EmailIntegrationConfigService(mapper, cipher);

        service.save(request("", ""), 7L);

        assertThat(cipher.decrypt(existing.getEncryptedDeepSeekApiKey())).isEqualTo("old-deepseek");
        assertThat(cipher.decrypt(existing.getEncryptedWeComSecret())).isEqualTo("old-wecom");
    }

    private EmailIntegrationConfigRequest request(String apiKey, String secret) {
        EmailIntegrationConfigRequest request = new EmailIntegrationConfigRequest();
        request.setDeepSeekEnabled(true);
        request.setDeepSeekBaseUrl("https://api.deepseek.com");
        request.setDeepSeekModel("deepseek-chat");
        request.setDeepSeekApiKey(apiKey);
        request.setWeComEnabled(true);
        request.setWeComBaseUrl("https://qyapi.weixin.qq.com");
        request.setWeComCorpId("corp-id");
        request.setWeComAgentId("1000002");
        request.setWeComSecret(secret);
        request.setPublicBaseUrl("http://192.168.1.241:18080");
        return request;
    }
}
