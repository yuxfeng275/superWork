package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("revenue_monthly_income")
public class RevenueMonthlyIncome {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String yearMonth;
    private Long projectId;
    private Long businessLineId;
    private Integer contractCount;
    private Long receivableAmount;
    private Long receivedAmount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
