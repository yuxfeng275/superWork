package com.bu.management.service;

import com.bu.management.config.YunxiaoProperties;
import com.bu.management.config.YunxiaoRuntimeConfig;
import com.bu.management.config.YunxiaoTokenCipher;
import com.bu.management.dto.YunxiaoConfigRequest;
import com.bu.management.entity.YunxiaoIntegrationConfig;
import com.bu.management.mapper.YunxiaoIntegrationConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class YunxiaoConfigServiceTest {

    private final AtomicReference<YunxiaoIntegrationConfig> stored = new AtomicReference<>();
    private YunxiaoConfigService service;

    @BeforeEach
    void setUp() {
        YunxiaoIntegrationConfigMapper mapper = mock(YunxiaoIntegrationConfigMapper.class);
        when(mapper.selectById(1L)).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(mapper).insert(any(YunxiaoIntegrationConfig.class));
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(mapper).updateById(any(YunxiaoIntegrationConfig.class));

        YunxiaoProperties properties = new YunxiaoProperties();
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 11);
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(key));
        service = new YunxiaoConfigService(mapper, properties, new YunxiaoTokenCipher(properties));
    }

    @Test
    void savesPageConfigurationAndNeverStoresTokenAsPlaintext() {
        YunxiaoRuntimeConfig runtime = service.save(request("first-token"), 16L);

        assertThat(runtime.isConfigured()).isTrue();
        assertThat(runtime.token()).isEqualTo("first-token");
        assertThat(runtime.tokenSource()).isEqualTo("PAGE");
        assertThat(stored.get().getEncryptedToken())
                .startsWith("v1:")
                .doesNotContain("first-token");
        assertThat(stored.get().getUpdatedBy()).isEqualTo(16L);
    }

    @Test
    void blankTokenKeepsPreviouslyEncryptedValue() {
        service.save(request("first-token"), 16L);
        String encrypted = stored.get().getEncryptedToken();
        YunxiaoConfigRequest update = request(null);
        update.setOrganizationId("new-org");

        YunxiaoRuntimeConfig runtime = service.save(update, 17L);

        assertThat(stored.get().getEncryptedToken()).isEqualTo(encrypted);
        assertThat(runtime.token()).isEqualTo("first-token");
        assertThat(runtime.organizationId()).isEqualTo("new-org");
    }

    @Test
    void enabledCenterEditionRequiresOrganizationId() {
        YunxiaoConfigRequest request = request("token");
        request.setOrganizationId(" ");

        assertThatThrownBy(() -> service.save(request, 16L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("组织ID");
    }

    @Test
    void serviceAddressMustBeAnHttpRootUrl() {
        YunxiaoConfigRequest request = request("token");
        request.setBaseUrl("https://example.com/private/path");

        assertThatThrownBy(() -> service.save(request, 16L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("根地址");
    }


    @Test
    void unreadableStoredTokenDegradesToRecoverableConfigError() {
        YunxiaoProperties encryptedProperties = new YunxiaoProperties();
        byte[] originalKey = new byte[32];
        java.util.Arrays.fill(originalKey, (byte) 3);
        encryptedProperties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(originalKey));
        YunxiaoIntegrationConfig config = new YunxiaoIntegrationConfig();
        config.setId(1L);
        config.setEnabled(1);
        config.setEdition("center");
        config.setBaseUrl("https://openapi-rdc.aliyuncs.com");
        config.setOrganizationId("org-1");
        config.setEncryptedToken(new YunxiaoTokenCipher(encryptedProperties).encrypt("lost-token"));
        stored.set(config);

        YunxiaoProperties missingKey = new YunxiaoProperties();
        YunxiaoIntegrationConfigMapper mapper = mock(YunxiaoIntegrationConfigMapper.class);
        when(mapper.selectById(1L)).thenReturn(config);
        YunxiaoConfigService recoveryService = new YunxiaoConfigService(
                mapper, missingKey, new YunxiaoTokenCipher(missingKey));

        YunxiaoRuntimeConfig runtime = recoveryService.getRuntimeConfig();

        assertThat(runtime.enabled()).isTrue();
        assertThat(runtime.isConfigured()).isFalse();
        assertThat(runtime.token()).isNull();
        assertThat(runtime.tokenSource()).isEqualTo("UNREADABLE");
        assertThat(runtime.lastTestStatus()).isEqualTo("CONFIG_ERROR");
        assertThat(runtime.lastTestMessage()).contains("重新录入");
    }

    @Test
    void unreadableStoredTokenRequiresReplacementBeforeSavingEnabledConfig() {
        YunxiaoProperties encryptedProperties = new YunxiaoProperties();
        byte[] originalKey = new byte[32];
        java.util.Arrays.fill(originalKey, (byte) 4);
        encryptedProperties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(originalKey));
        YunxiaoIntegrationConfig config = new YunxiaoIntegrationConfig();
        config.setId(1L);
        config.setEnabled(1);
        config.setEdition("center");
        config.setBaseUrl("https://openapi-rdc.aliyuncs.com");
        config.setOrganizationId("org-1");
        config.setEncryptedToken(new YunxiaoTokenCipher(encryptedProperties).encrypt("lost-token"));
        stored.set(config);

        YunxiaoProperties replacementProperties = new YunxiaoProperties();
        byte[] replacementKey = new byte[32];
        java.util.Arrays.fill(replacementKey, (byte) 5);
        replacementProperties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(replacementKey));
        YunxiaoIntegrationConfigMapper mapper = mock(YunxiaoIntegrationConfigMapper.class);
        when(mapper.selectById(1L)).thenReturn(config);
        YunxiaoConfigService recoveryService = new YunxiaoConfigService(
                mapper, replacementProperties, new YunxiaoTokenCipher(replacementProperties));

        YunxiaoConfigRequest update = request(null);
        assertThatThrownBy(() -> recoveryService.save(update, 16L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("重新录入");
    }

    private YunxiaoConfigRequest request(String token) {
        YunxiaoConfigRequest request = new YunxiaoConfigRequest();
        request.setEnabled(true);
        request.setEdition("center");
        request.setBaseUrl("https://openapi-rdc.aliyuncs.com/");
        request.setOrganizationId("org-1");
        request.setToken(token);
        return request;
    }
}
