package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("email_message")
public class EmailMessage {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long ownerUserId;
  private Long accountId;
  private Long projectId;
  private String folder;
  private Long uidValidity;
  private Long uid;
  private String internetMessageId;
  private String subject;
  private String senderName;
  private String senderAddress;
  private String toAddressesJson;
  private String ccAddressesJson;
  private LocalDateTime receivedAt;
  private String bodyPreview;
  private String bodyText;
  private String attachmentsJson;
  private String aiInterpretationStatus;
  private String aiInterpretationJson;
  private String aiInterpretationModel;
  private String aiInterpretationError;
  private LocalDateTime aiInterpretedAt;
  private String groupingStatus;
  private String groupingMethod;
  private java.math.BigDecimal groupingConfidence;
  private String groupingReason;
  private String groupingModel;
  private LocalDateTime groupedAt;
  private LocalDateTime createdAt;
}
