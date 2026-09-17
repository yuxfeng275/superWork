package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("yunxiao_worklog_snapshot")
public class YunxiaoWorklogSnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate workDate;
    private BigDecimal expectedHours;
    private BigDecimal actualHours;
    private String status;
    private Integer isFinal;
    private String source;
    private LocalDateTime computedAt;
}
