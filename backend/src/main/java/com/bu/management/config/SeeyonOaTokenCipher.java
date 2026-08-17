package com.bu.management.config;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SeeyonOaTokenCipher {

    private static final String ALGORITHM = "AES";
    private static final byte[] FALLBACK_KEY = "seeyon-oa-cipher".getBytes(StandardCharsets.UTF_8);

    private final byte[] keyBytes;

    public SeeyonOaTokenCipher(SeeyonOaProperties properties) {
        String configured = properties.getConfigEncryptionKey();
        if (StringUtils.hasText(configured)) {
            byte[] raw = configured.getBytes(StandardCharsets.UTF_8);
            this.keyBytes = new byte[16];
            System.arraycopy(raw, 0, this.keyBytes, 0, Math.min(raw.length, 16));
        } else {
            this.keyBytes = new byte[16];
            System.arraycopy(FALLBACK_KEY, 0, this.keyBytes, 0, Math.min(FALLBACK_KEY.length, 16));
        }
    }

    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            SecretKeySpec key = new SecretKeySpec(keyBytes, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("OA 令牌加密失败", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null) return null;
        try {
            SecretKeySpec key = new SecretKeySpec(keyBytes, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedText));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("OA 令牌解密失败", e);
        }
    }
}