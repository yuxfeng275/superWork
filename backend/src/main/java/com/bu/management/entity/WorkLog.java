package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工时记录表
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
@TableName("work_log")
public class WorkLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 任务ID
     */
    private Long taskId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 工作日期
     */
    private LocalDate workDate;

    /**
     * 工时（小时）
     */
    private BigDecimal hours;

    /**
     * 工作内容描述（表列名 description）
     */
    @com.baomidou.mybatisplus.annotation.TableField("description")
    private String workContent;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
