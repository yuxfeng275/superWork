package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@TableName("yunxiao_workday_calendar")
public class YunxiaoWorkday {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate workDate;
    private Integer isWorkday;
    private BigDecimal expectedHours;
    private String source;
}
