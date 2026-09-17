package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yunxiao_handoff_event")
public class YunxiaoHandoffEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long requirementId;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
