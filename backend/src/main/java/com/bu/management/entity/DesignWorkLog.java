package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设计工作记录表
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
@TableName("design_work_log")
public class DesignWorkLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 需求ID
     */
    private Long requirementId;

    /**
     * 工作类型：原型设计/UI设计/技术方案设计
     */
    private String workType;

    /**
     * 设计人ID
     */
    private Long designerId;

    /**
     * 预估工时
     */
    private BigDecimal estimatedHours;

    /**
     * 实际工时
     */
    private BigDecimal actualHours;

    /**
     * 成果物链接或文件ID
     */
    private String resultUrl;

    /**
     * 工作内容描述
     */
    private String workContent;

    /**
     * 计划完成时间
     */
    private LocalDateTime plannedCompletedAt;

    /**
     * 状态：待开始/进行中/已完成
     */
    private String status;

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
