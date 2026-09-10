package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("kpi_deviation_note")
public class KpiDeviationNote {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long snapshotId;
    /** revenue/profit */
    private String metric;
    /** red=环比<=0 / yellow=明显偏小 */
    private String alertLevel;
    private String deviationReason;
    /** 1=是 0=否 */
    private Integer isAbnormal;
    private String countermeasure;
    /** pending/done */
    private String status;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
