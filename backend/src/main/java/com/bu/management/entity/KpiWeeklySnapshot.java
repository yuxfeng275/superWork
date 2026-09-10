package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("kpi_weekly_snapshot")
public class KpiWeeklySnapshot {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 周截止日（周日） */
    private LocalDate weekEndDate;
    private String reportGroup;
    private BigDecimal ytdRevenue;
    private BigDecimal ytdDirectCost;
    private BigDecimal ytdLaborCost;
    private BigDecimal ytdOtherCost;
    private BigDecimal ytdProfit;
    /** 周环比增量-营收（相对上一个快照） */
    private BigDecimal weekDeltaRevenue;
    /** 周环比增量-毛利 */
    private BigDecimal weekDeltaProfit;
    /** 1=含当月估算（未月结） */
    private Integer estimated;
    private LocalDateTime createdAt;
}
