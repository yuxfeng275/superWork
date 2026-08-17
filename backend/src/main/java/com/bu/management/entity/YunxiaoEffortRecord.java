package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("yunxiao_effort_record")
public class YunxiaoEffortRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String yunxiaoRecordId;
    private String yunxiaoWorkitemId;
    private Long projectId;
    private String yunxiaoUserId;
    private String userName;
    private LocalDate workDate;
    private BigDecimal actualHours;
    private String description;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
