package com.bu.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@Component
@ConfigurationProperties(prefix = "email")
public class EmailProperties {
  private String imapHost = "imap.qiye.aliyun.com";
  private int imapPort = 993;
  private int connectionTimeoutMillis = 10_000;
  private int readTimeoutMillis = 30_000;
  private String credentialEncryptionKey;
  private String syncCron = "0 0 * * * *";
  private String digestCron = "0 0 8 * * *";

  public boolean isCredentialEncryptionConfigured() {
    return StringUtils.hasText(credentialEncryptionKey);
  }
}
