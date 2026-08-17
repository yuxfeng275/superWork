package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("email_daily_digest")
public class EmailDailyDigest {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long ownerUserId;
  private LocalDate digestDate;
  private Integer messageCount;
  private String status;
  private String generationMode;
  private String overview;
  private String importantItems;
  private String todoItems;
  private String riskItems;
  private String replyItems;
  private String errorMessage;
  private String pushStatus;
  private Integer pushAttempts;
  private String pushError;
  private LocalDateTime pushedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
