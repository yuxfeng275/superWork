package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 财报月度收入数据，用于与系统收入对齐。
 * 当系统收入 < 财报收入时，差额以「财务调节」行补充。
 */
@Data
@TableName("revenue_financial_report")
public class RevenueFinancialReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("`year_month`")
    private String yearMonth;
    private Long businessLineId;
    /** 财报未税收入（元） */
    private BigDecimal revenueAmount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}