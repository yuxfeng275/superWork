package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 营收其他成本手动维护：协力成本(partner)/服务器成本(server)/其他成本(other)，
 * 按 月份×业务线×项目（项目可空=业务线级）。
 */
@Data
@TableName("revenue_other_cost")
public class RevenueOtherCost {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("`year_month`")
    private String yearMonth;
    private Long businessLineId;
    /** NULL=业务线级 */
    private Long projectId;
    /** partner=协力/server=服务器/other=其他 */
    private String costType;
    /** 金额（元） */
    private BigDecimal amountYuan;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
