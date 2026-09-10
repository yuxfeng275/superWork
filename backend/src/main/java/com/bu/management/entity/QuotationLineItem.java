package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data
@TableName("quotation_line_item")
public class QuotationLineItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long quotationId;
    private Long policyItemId;
    private String section;
    private String category;
    private String itemName;
    private String description;
    private String priceDescription;
    private Integer isSelected;
    private BigDecimal quantity;
    private BigDecimal unitPriceExTax;
    private BigDecimal taxRate;
    private BigDecimal discountRate;
    private BigDecimal subtotalExTax;
    private BigDecimal subtotalInclTax;
    private String chargeMethod;
    private String remark;
    private Integer sortOrder;
}