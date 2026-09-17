package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("worktime_business_line_mapping")
public class WorktimeBusinessLineMapping {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 工时系统业务线ID */
    private Long worktimeBusinessLineId;
    private String worktimeBusinessLineName;
    /** 本系统业务线ID */
    private Long businessLineId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
