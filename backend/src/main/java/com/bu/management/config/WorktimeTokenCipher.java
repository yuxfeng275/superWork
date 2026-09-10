package com.bu.management.config;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 工时系统配置加解密（AES，模式同 SeeyonOaTokenCipher）。
 */
@Component
public class WorktimeTokenCipher {

    private static final String ALGORITHM = "AES";
    private static final byte[] FALLBACK_KEY = "worktime-cipher!".getBytes(StandardCharsets.UTF_8);

    private final byte[] keyBytes;

    public WorktimeTokenCipher(WorktimeProperties properties) {
        String configured = properties.getConfigEncryptionKey();
        byte[] raw = StringUtils.hasText(configured)
                ? configured.getBytes(StandardCharsets.UTF_8) : FALLBACK_KEY;
        this.keyBytes = new byte[16];
        System.arraycopy(raw, 0, this.keyBytes, 0, Math.min(raw.length, 16));
    }

    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            SecretKeySpec key = new SecretKeySpec(keyBytes, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("工时系统配置加密失败", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null) return null;
        try {
            SecretKeySpec key = new SecretKeySpec(keyBytes, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return new String(cipher.doFinal(Base64.getDecoder().decode(encryptedText)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("工时系统配置解密失败", e);
        }
    }
}
