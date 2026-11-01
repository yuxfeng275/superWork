package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /**
     * 可选段落允许清空：默认 NOT_NULL 策略会把 null 从 UPDATE 里跳过，
     * 导致用户清空「问题/风险」等字段后旧值仍然留在库里（周进展无法保持）。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String issues;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String nextWeekPlan;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String supportNeeded;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
