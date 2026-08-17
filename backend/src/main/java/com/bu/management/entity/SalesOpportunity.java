package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sales_opportunity")
public class SalesOpportunity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String customer;
    private String type;
    private String status;
    private BigDecimal amount;
    private String owner;
    private String businessLine;
    private String nextFollowUp;
    private LocalDateTime createdAt;
    private Integer probability;
    private LocalDate expectedClose;
    private String source;
    private String note;
}
