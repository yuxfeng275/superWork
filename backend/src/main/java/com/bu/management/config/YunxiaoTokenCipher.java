package com.bu.management.config;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class YunxiaoTokenCipher {

    private static final String VERSION_PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final YunxiaoProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public YunxiaoTokenCipher(YunxiaoProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(properties.getConfigEncryptionKey());
    }

    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            throw new IllegalArgumentException("云效令牌不能为空");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("云效令牌加密失败", ex);
        }
    }

    public String decrypt(String encryptedValue) {
        if (!StringUtils.hasText(encryptedValue) || !encryptedValue.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("云效令牌密文格式无效");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(
                    encryptedValue.substring(VERSION_PREFIX.length()));
            if (payload.length <= IV_BYTES) {
                throw new IllegalStateException("云效令牌密文格式无效");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            System.arraycopy(payload, IV_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("云效令牌解密失败，请重新录入令牌", ex);
        }
    }

    private SecretKeySpec encryptionKey() {
        if (!isConfigured()) {
            throw new IllegalStateException("服务器未配置云效令牌加密密钥，暂不能保存令牌");
        }
        try {
            byte[] key = Base64.getDecoder().decode(properties.getConfigEncryptionKey().trim());
            if (key.length != 32) {
                throw new IllegalStateException("云效令牌加密密钥必须为 32 字节 Base64");
            }
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("云效令牌加密密钥不是有效的 Base64", ex);
        }
    }
}
