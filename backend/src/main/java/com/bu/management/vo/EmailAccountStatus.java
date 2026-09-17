package com.bu.management.vo;

import java.time.LocalDateTime;

public record EmailAccountStatus(
    boolean configured,
    boolean enabled,
    String provider,
    String emailAddress,
    boolean credentialConfigured,
    String connectionStatus,
    String connectionMessage,
    LocalDateTime lastTestedAt,
    LocalDateTime lastSyncAt,
    String lastSyncStatus,
    String lastSyncMessage) {}
