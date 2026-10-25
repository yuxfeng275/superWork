package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("quotation_approval_log")
public class QuotationApprovalLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long quotationId;
    private String fromStatus;
    private String toStatus;
    private String action;
    private String operator;
    private String comment;
    private LocalDateTime createdAt;
}
