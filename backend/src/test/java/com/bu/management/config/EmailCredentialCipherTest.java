package com.bu.management.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class EmailCredentialCipherTest {
  @Test
  void encryptsWithRandomIvAndRoundTrips() {
    EmailProperties properties = new EmailProperties();
    properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
    EmailCredentialCipher cipher = new EmailCredentialCipher(properties);
    String first = cipher.encrypt("app-secret");
    String second = cipher.encrypt("app-secret");
    assertThat(first).startsWith("v1:").isNotEqualTo(second);
    assertThat(cipher.decrypt(first)).isEqualTo("app-secret");
  }

  @Test
  void requiresExactly256BitKey() {
    EmailProperties properties = new EmailProperties();
    properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));
    EmailCredentialCipher cipher = new EmailCredentialCipher(properties);
    assertThatThrownBy(() -> cipher.encrypt("secret")).hasMessageContaining("32");
  }
}
