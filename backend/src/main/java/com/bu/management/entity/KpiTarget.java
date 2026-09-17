package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("kpi_target")
public class KpiTarget {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("`year`")
    private Integer year;
    /** 报表行分组（会员通/全渠道云鹿/全渠道精准） */
    private String reportGroup;
    /** 年度营收目标（元） */
    private BigDecimal revenueTarget;
    /** 年度毛利目标（元） */
    private BigDecimal profitTarget;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
