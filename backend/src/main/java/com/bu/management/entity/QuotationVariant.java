package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("quotation_variant")
public class QuotationVariant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long opportunityId;
    private String name;
    private String description;
    private Long latestQuotationId;
    private String variantStatus;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
