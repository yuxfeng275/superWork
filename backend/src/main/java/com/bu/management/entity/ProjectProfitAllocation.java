package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 项目利润手动分配（月 × 目标 × 列，金额单位：元、工时单位：人月，允许负数作调整项）。
 * 目标：target_type=project（full 线根项目 / aggregate 项目集 / simple 单行，后两者 project_id=0）、
 * sales（销售行，project_id=0）、line_other（full 线「业务线」行，project_id=0）。
 * 列（cost_type）：revenue/sms/direct/platform_fee/compensation/outsourcing/software_gift/hours/cost，
 * 差额所有项均可分配，差额归零后前端自动隐藏差额行。
 */
@Data
@TableName("project_profit_allocation")
public class ProjectProfitAllocation {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("`year_month`")
    private String yearMonth;
    private Long businessLineId;
    /** 本系统项目ID（主项目）；0=非项目目标（销售/业务线/项目集/精准单行） */
    private Long projectId;
    /** 分配目标：project/sales/line_other */
    private String targetType;
    /** revenue/sms/direct/platform_fee/compensation/outsourcing/software_gift/hours/cost */
    private String costType;
    /** 分配数量（金额：元；hours：人月；允许负数作调整） */
    private BigDecimal amount;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
