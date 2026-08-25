package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("revenue_manual_entry")
public class RevenueManualEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String yearMonth;
    private Long projectId;
    private Long businessLineId;
    private String entryType;
    private Long amount;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
