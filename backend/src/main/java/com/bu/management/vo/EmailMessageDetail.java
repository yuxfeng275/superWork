package com.bu.management.vo;

import com.bu.management.integration.AlibabaMailClient.AttachmentMeta;
import java.time.LocalDateTime;
import java.util.List;

public record EmailMessageDetail(
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
    String groupingReason,
    List<String> toAddresses,
    List<String> ccAddresses,
    String textBody,
    List<AttachmentMeta> attachments,
    EmailInterpretationView interpretation) {}
