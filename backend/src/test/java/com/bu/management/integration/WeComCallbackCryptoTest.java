package com.bu.management.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 企微回调加解密的协议契约：签名可验证、密文可往返、receiveId 不匹配必须拒绝。
 */
class WeComCallbackCryptoTest {

    private static final String CORP_ID = "ww-test-corp";
    private static final String TOKEN = "callback-token";
    private static final String AES_KEY = randomKey();

    private WeComCallbackCrypto crypto;

    @BeforeEach
    void setUp() {
        crypto = new WeComCallbackCrypto();
        ReflectionTestUtils.setField(crypto, "token", TOKEN);
        ReflectionTestUtils.setField(crypto, "aesKey", AES_KEY);
        ReflectionTestUtils.setField(crypto, "corpId", CORP_ID);
    }

    @Test
    @DisplayName("加密→解密往返一致，签名可验证")
    void encryptDecryptRoundTrip() {
        String message = "<xml><Content><![CDATA[客户说下周给答复]]></Content></xml>";
        String random16 = "0123456789abcdef";
        String encrypted = crypto.encrypt(message, random16);

        assertThat(crypto.decrypt(encrypted)).isEqualTo(message);
        String signature = crypto.sign("1730000000", "nonce-1", encrypted);
        assertThat(crypto.verify(signature, "1730000000", "nonce-1", encrypted)).isTrue();
        assertThat(crypto.verify(signature, "1730000001", "nonce-1", encrypted)).isFalse();
    }

    @Test
    @DisplayName("receiveId 不匹配（密文不是发给本企业）必须拒绝")
    void rejectsWrongCorpId() {
        String encrypted = crypto.encrypt("<xml/>", "0123456789abcdef");
        ReflectionTestUtils.setField(crypto, "corpId", "other-corp");
        assertThatThrownBy(() -> crypto.decrypt(encrypted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receiveId");
    }

    @Test
    @DisplayName("未配置或密钥长度不对：configured=false，不猜测")
    void notConfiguredFailsClosed() {
        WeComCallbackCrypto empty = new WeComCallbackCrypto();
        assertThat(empty.configured()).isFalse();

        ReflectionTestUtils.setField(crypto, "aesKey", "too-short");
        assertThat(crypto.configured()).isFalse();
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }
}
