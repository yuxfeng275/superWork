package com.bu.management.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YunxiaoTokenCipherTest {

    @Test
    void encryptsWithRandomIvAndDecryptsTheOriginalToken() {
        YunxiaoProperties properties = propertiesWithKey((byte) 7);
        YunxiaoTokenCipher cipher = new YunxiaoTokenCipher(properties);

        String first = cipher.encrypt("yunxiao-secret-token");
        String second = cipher.encrypt("yunxiao-secret-token");

        assertThat(first).startsWith("v1:").doesNotContain("yunxiao-secret-token");
        assertThat(second).isNotEqualTo(first);
        assertThat(cipher.decrypt(first)).isEqualTo("yunxiao-secret-token");
    }

    @Test
    void refusesToEncryptWithoutServerKey() {
        YunxiaoTokenCipher cipher = new YunxiaoTokenCipher(new YunxiaoProperties());

        assertThatThrownBy(() -> cipher.encrypt("token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("加密密钥");
    }

    private YunxiaoProperties propertiesWithKey(byte fill) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, fill);
        YunxiaoProperties properties = new YunxiaoProperties();
        properties.setConfigEncryptionKey(Base64.getEncoder().encodeToString(key));
        return properties;
    }
}
