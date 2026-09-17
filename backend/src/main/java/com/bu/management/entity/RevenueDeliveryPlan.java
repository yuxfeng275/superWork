package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预估交付计划：项目×月份 批量录入。
 * amountYuan = 预估交付金额（元）；laborCostYuan = 预估人月 × 历史完结累计单价快照。
 */
@Data
@TableName("revenue_delivery_plan")
public class RevenueDeliveryPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("`year_month`")
    private String yearMonth;
    private Long businessLineId;
    /** NULL=业务线聚合行（会员通项目集） */
    private Long projectId;
    /** 预估交付金额（元） */
    private BigDecimal amountYuan;
    /** 预估人月 */
    private BigDecimal personMonths;
    /** 预估工时成本（元） */
    private BigDecimal laborCostYuan;
    /** 历史完结累计单价快照（元/人月） */
    private BigDecimal unitPriceSnapshot;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
