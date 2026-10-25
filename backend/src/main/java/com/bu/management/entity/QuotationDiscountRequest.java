package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quotation_discount_request")
public class QuotationDiscountRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long quotationId;
    private String requestedBy;
    private BigDecimal requestedDiscount;
    private BigDecimal floorDiscount;
    private BigDecimal grossMarginRate;
    private String reason;
    private String status;
    private String approver;
    private LocalDateTime approvedAt;
    private String comment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
