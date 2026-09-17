package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营收月结标记：存在记录即该月已完结，完结后展示实际值并锁定导入/预估。
 */
@Data
@TableName("revenue_month_close")
public class RevenueMonthClose {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("`year_month`")
    private String yearMonth;
    private LocalDateTime closedAt;
    private Long closedBy;
}
