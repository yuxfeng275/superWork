package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quotation_policy_item")
public class QuotationPolicyItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long policyId;
    private String section;
    private String category;
    private String itemKey;
    private String itemName;
    private String description;
    private String priceDescription;
    private Integer isRequired;
    private BigDecimal unitPrice;
    private BigDecimal taxRate;
    private String chargeMethod;
    private String chargeUnit;
    private String remark;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}