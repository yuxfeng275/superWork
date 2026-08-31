package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 营收成本明细（按月从成本分析 Excel 导入，金额单位：元）
 */
@Data
@TableName("revenue_cost_entry")
public class RevenueCostEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    @TableField("`year_month`")
    private String yearMonth;
    private String businessLineName;
    private Long businessLineId;
    private String projectNameRaw;
    private Long projectId;
    /** project/sales */
    private String workType;
    private String salesKind;
    private Long salesProjectId;
    private Integer employeeCount;
    /** 人月 */
    private BigDecimal hours;
    /** 工时成本（元） */
    private BigDecimal costAmount;
    /** 人月成本（元/人月） */
    private BigDecimal personMonthCost;
    private Integer pending;
    private LocalDateTime createdAt;
}
