package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("yunxiao_effort_exemption")
public class YunxiaoEffortExemption {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate workDate;
    private String reason;
    private Long createdBy;
    private LocalDateTime createdAt;
}
