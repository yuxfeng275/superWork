package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("email_wecom_mapping")
public class EmailWeComMapping {
  @TableId(type = IdType.AUTO)
  private Long id;

  private Long ownerUserId;
  private String wecomUserId;
  private Integer enabled;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
