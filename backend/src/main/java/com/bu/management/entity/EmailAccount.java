package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("email_account")
public class EmailAccount {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long ownerUserId;
  private String emailAddress;
  private String encryptedCredential;
  private Integer enabled;
  /** 模式：PERSONAL=个人邮箱（缺省） / ARCHIVE=归档共享邮箱 */
  private String mode;
  /** 归档模式的共享范围标记（如部门/全员），当前仅落库 */
  private String sharedScope;
  private String connectionStatus;
  private String connectionMessage;
  private LocalDateTime lastTestedAt;
  private String syncStatus;
  private String syncError;
  private Integer lastSyncCount;
  private Long uidValidity;
  private Long lastUid;
  private LocalDateTime initialSyncFrom;
  private LocalDateTime lastSyncStartedAt;
  private LocalDateTime lastSyncCompletedAt;
  private LocalDateTime lockUntil;
  private String lockToken;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
