package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 业务线月度利润报表（数据源自工时系统，整月覆盖同步，金额单位：元，比率：百分数，工时：人月）
 */
@Data
@TableName("biz_line_profit_report")
public class BizLineProfitReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("`year_month`")
    private String yearMonth;
    private Long worktimeBusinessLineId;
    private String worktimeBusinessLineName;
    private Long businessLineId;
    private String groupName;
    /** 营业收入 */
    private BigDecimal revenue;
    private BigDecimal smsCost;
    private BigDecimal directCost;
    private BigDecimal platformFee;
    private BigDecimal compensation;
    private BigDecimal outsourcing;
    private BigDecimal softwareGift;
    /** 工时（人月） */
    private BigDecimal totalHours;
    private BigDecimal hoursRatio;
    @TableField("expense_1")
    private BigDecimal expense1;
    @TableField("labor_cost_1")
    private BigDecimal laborCost1;
    /** 考核毛利 */
    private BigDecimal grossProfit;
    private BigDecimal grossProfitRate;
    private BigDecimal marketingCost;
    @TableField("labor_cost_2_sales")
    private BigDecimal laborCost2Sales;
    @TableField("labor_cost_3_backend")
    private BigDecimal laborCost3Backend;
    @TableField("labor_cost_3_tech")
    private BigDecimal laborCost3Tech;
    @TableField("labor_cost_3_rd")
    private BigDecimal laborCost3Rd;
    @TableField("expense_2")
    private BigDecimal expense2;
    /** 净利润 */
    private BigDecimal netProfit;
    private BigDecimal netProfitRate;
    private LocalDateTime syncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
