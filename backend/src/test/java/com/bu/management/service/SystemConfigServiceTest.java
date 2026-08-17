package com.bu.management.service;

import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.config.EmailProperties;
import com.bu.management.dto.SystemConfigGroupRequest;
import com.bu.management.entity.SystemConfigItem;
import com.bu.management.mapper.SystemConfigItemMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemConfigServiceTest {
    @Test
    void encryptsSensitiveValuesAndNeverReturnsThem() {
        SystemConfigItemMapper mapper = mock(SystemConfigItemMapper.class);
        List<SystemConfigItem> items = List.of(
                item(1L, "deepseek.enabled", "启用 DeepSeek", "BOOLEAN", "false", false, true, 10),
                item(2L, "deepseek.api-key", "DeepSeek API Key", "PASSWORD", null, true, false, 20));
        when(mapper.selectList(any())).thenReturn(items);
        doAnswer(invocation -> 1).when(mapper).updateById(any(SystemConfigItem.class));
        EmailProperties properties = new EmailProperties();
        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        EmailCredentialCipher cipher = new EmailCredentialCipher(properties);
        SystemConfigService service = new SystemConfigService(mapper, cipher);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("deepseek.enabled", "true");
        values.put("deepseek.api-key", "page-secret");
        SystemConfigGroupRequest request = new SystemConfigGroupRequest();
        request.setValues(values);

        var result = service.saveGroup("email-integration", request, 7L);

        assertThat(items.get(1).getConfigValue()).startsWith("v1:").doesNotContain("page-secret");
        assertThat(result.items().get(1).configured()).isTrue();
        assertThat(result.items().get(1).value()).isNull();
        assertThat(result.toString()).doesNotContain("page-secret").doesNotContain("v1:");
        assertThat(cipher.decrypt(items.get(1).getConfigValue())).isEqualTo("page-secret");
    }

    @Test
    void blankSensitiveInputPreservesExistingCiphertext() {
        SystemConfigItemMapper mapper = mock(SystemConfigItemMapper.class);
        EmailProperties properties = new EmailProperties();
        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        EmailCredentialCipher cipher = new EmailCredentialCipher(properties);
        SystemConfigItem secret = item(2L, "wecom.secret", "企业微信 Secret", "PASSWORD",
                cipher.encrypt("existing-secret"), true, false, 20);
        when(mapper.selectList(any())).thenReturn(List.of(secret));
        SystemConfigService service = new SystemConfigService(mapper, cipher);
        SystemConfigGroupRequest request = new SystemConfigGroupRequest();
        request.setValues(Map.of("wecom.secret", ""));

        service.saveGroup("email-integration", request, 7L);

        assertThat(cipher.decrypt(secret.getConfigValue())).isEqualTo("existing-secret");
    }

    private SystemConfigItem item(Long id, String key, String name, String type, String value,
                                  boolean sensitive, boolean required, int sortOrder) {
        SystemConfigItem item = new SystemConfigItem();
        item.setId(id);
        item.setGroupCode("email-integration");
        item.setGroupName("邮件摘要与推送");
        item.setGroupDescription("邮件集成配置");
        item.setConfigKey(key);
        item.setConfigName(name);
        item.setValueType(type);
        item.setConfigValue(value);
        item.setIsSensitive(sensitive ? 1 : 0);
        item.setIsRequired(required ? 1 : 0);
        item.setSortOrder(sortOrder);
        item.setStatus(1);
        return item;
    }
}
