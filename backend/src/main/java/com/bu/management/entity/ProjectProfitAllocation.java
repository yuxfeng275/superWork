package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 项目利润成本手动分配（月 × 项目 × 成本类型，金额单位：元，允许负数作调整项）。
 * 成本类型：sms/direct/platform_fee/compensation/outsourcing/software_gift。
 */
@Data
@TableName("project_profit_allocation")
public class ProjectProfitAllocation {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("`year_month`")
    private String yearMonth;
    private Long businessLineId;
    /** 本系统项目ID（主项目） */
    private Long projectId;
    /** sms/direct/platform_fee/compensation/outsourcing/software_gift */
    private String costType;
    /** 分配金额（元，允许负数作调整） */
    private BigDecimal amount;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
