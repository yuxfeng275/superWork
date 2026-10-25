package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("quotation_template_clause")
public class QuotationTemplateClause {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private String section;
    private String title;
    private String content;
    private Integer isEnabled;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
