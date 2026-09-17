package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营收销售项目注册表：Excel 中「京博【销售】」这类具体销售项目自动注册，
 * 可手动关联到系统中的商机。
 */
@Data
@TableName("revenue_sales_project")
public class RevenueSalesProject {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long businessLineId;
    private String name;
    private Long opportunityId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
