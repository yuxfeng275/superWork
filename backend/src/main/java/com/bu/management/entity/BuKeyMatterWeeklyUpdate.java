package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("bu_key_matter_weekly_update")
public class BuKeyMatterWeeklyUpdate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long keyMatterId;
    private LocalDate weekStartDate;
    private String status;
    private Integer progress;
    private String progressSummary;
    private String issues;
    private String nextWeekPlan;
    private String supportNeeded;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
