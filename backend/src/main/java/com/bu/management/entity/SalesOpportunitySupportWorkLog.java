package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sales_opportunity_support_worklog")
public class SalesOpportunitySupportWorkLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long opportunityId;
    private LocalDate supportDate;
    private String supporter;
    private BigDecimal hours;
    private String supportType;
    private String content;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
