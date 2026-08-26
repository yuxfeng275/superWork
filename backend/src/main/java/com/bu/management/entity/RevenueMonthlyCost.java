package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("revenue_monthly_cost")
public class RevenueMonthlyCost {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("`year_month`")
    private String yearMonth;
    private Long projectId;
    private Long businessLineId;
    private String category;
    private BigDecimal workHours;
    private Long workCost;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
