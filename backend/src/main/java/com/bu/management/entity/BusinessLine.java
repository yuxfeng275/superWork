package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务线实体
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
@TableName("business_line")
public class BusinessLine {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 业务线名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 状态：1=启用，0=禁用
     */
    private Integer status;

    /**
     * 营收矩阵展示模式：full=项目+销售明细行 / aggregate=项目销售两行聚合 / simple=单行汇总
     */
    private String revenueMode;

    /**
     * 营收矩阵是否展示成本：1=展示，0=只统计工时（成本计入公司公共投入）
     */
    private Integer costVisible;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
