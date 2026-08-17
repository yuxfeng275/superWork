package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sales_opportunity_follow_up")
public class SalesOpportunityFollowUp {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long opportunityId;
    private LocalDateTime followUpAt;
    private String follower;
    private String content;
    private String status;
    private Integer probability;
    private String nextFollowUp;
    private LocalDateTime createdAt;
}
