package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("kpi_alert_rule")
public class KpiAlertRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 报表行分组；NULL=全局默认 */
    private String reportGroup;
    /** 周基准 = 月均目标 ÷ 该值（默认 4.33） */
    private BigDecimal weeklyDivisor;
    /** 环比增量 < 周基准 × 该比例 判"明显偏小"（默认 0.5） */
    private BigDecimal yellowRatio;
    private Integer enabled;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
