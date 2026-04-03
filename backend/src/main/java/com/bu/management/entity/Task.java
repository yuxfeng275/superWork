package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 任务表
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
@TableName("task")
public class Task {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 需求ID
     */
    private Long requirementId;

    /**
     * 任务标题
     */
    private String title;

    /**
     * 任务描述
     */
    private String description;

    /**
     * 负责人ID
     */
    private Long assigneeId;

    /**
     * 预估工时
     */
    private BigDecimal estimatedHours;

    /**
     * 实际工时
     */
    private BigDecimal actualHours;

    /**
     * 状态：待开始/进行中/已完成
     */
    private String status;

    /**
     * 优先级：低/中/高
     */
    private String priority;

    /**
     * 开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
