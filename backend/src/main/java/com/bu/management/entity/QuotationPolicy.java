package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("quotation_policy")
public class QuotationPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String type;
    private String taxMode;
    private Integer version;
    private String status;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}