package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("quotation_template")
public class QuotationTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String type;
    private String title;
    private String issuer;
    private Integer defaultValidityDays;
    private String defaultDeliveryPeriod;
    private String invoiceType;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
