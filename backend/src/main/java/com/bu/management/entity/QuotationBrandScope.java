package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("quotation_brand_scope")
public class QuotationBrandScope {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long quotationId;
    private String brand;
    private String store;
    private String description;
    private String target;
    private Integer sortOrder;
}