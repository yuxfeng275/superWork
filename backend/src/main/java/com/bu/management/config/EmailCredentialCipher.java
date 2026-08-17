package com.bu.management.config;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EmailCredentialCipher {
  private static final String PREFIX = "v1:";
  private static final int IV = 12;
  private final EmailProperties properties;
  private final SecureRandom random = new SecureRandom();

  public EmailCredentialCipher(EmailProperties properties) {
    this.properties = properties;
  }

  public String encrypt(String value) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException("邮箱安全密码不能为空");
    try {
      byte[] iv = new byte[IV];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      byte[] out = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
      return PREFIX
          + Base64.getEncoder()
              .encodeToString(ByteBuffer.allocate(iv.length + out.length).put(iv).put(out).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("邮箱凭据加密失败", e);
    }
  }

  public String decrypt(String value) {
    if (!StringUtils.hasText(value) || !value.startsWith(PREFIX))
      throw new IllegalStateException("邮箱凭据密文格式无效");
    try {
      byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
      if (all.length <= IV) throw new IllegalStateException("邮箱凭据密文格式无效");
      byte[] iv = new byte[IV], data = new byte[all.length - IV];
      System.arraycopy(all, 0, iv, 0, IV);
      System.arraycopy(all, IV, data, 0, data.length);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      return new String(c.doFinal(data), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("邮箱凭据解密失败，请重新绑定", e);
    }
  }

  private SecretKeySpec key() {
    if (!properties.isCredentialEncryptionConfigured())
      throw new IllegalStateException("服务器未配置邮箱凭据加密密钥");
    try {
      byte[] key = Base64.getDecoder().decode(properties.getCredentialEncryptionKey().trim());
      if (key.length != 32) throw new IllegalStateException("邮箱凭据加密密钥必须为 32 字节 Base64");
      return new SecretKeySpec(key, "AES");
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("邮箱凭据加密密钥不是有效 Base64", e);
    }
  }
}
