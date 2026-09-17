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
    Long projectId,
    String projectName,
    String projectFullPath,
    String groupingStatus,
    java.math.BigDecimal groupingConfidence,
    boolean hasAttachments,
    int attachmentCount) {}
