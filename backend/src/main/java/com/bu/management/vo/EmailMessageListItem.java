package com.bu.management.vo;

import java.time.LocalDateTime;

public record EmailMessageListItem(
    Long id,
    String messageId,
    String subject,
    String fromName,
    String fromAddress,
    LocalDateTime receivedAt,
    String preview,
    boolean hasAttachments,
    int attachmentCount) {}
