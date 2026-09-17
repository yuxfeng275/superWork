package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 营收预估明细（未完结月份手工维护，金额 = 人月 × 历史完结人均成本快照）
 */
@Data
@TableName("revenue_estimate_entry")
public class RevenueEstimateEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("`year_month`")
    private String yearMonth;
    private Long businessLineId;
    /** NULL=业务线级（项目集/商机集合/其他） */
    private Long projectId;
    /** project/sales */
    private String workType;
    private String salesKind;
    private Long salesProjectId;
    private String description;
    private BigDecimal personMonths;
    /** 元/人月 */
    private BigDecimal unitPrice;
    /** 元 */
    private BigDecimal amount;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
