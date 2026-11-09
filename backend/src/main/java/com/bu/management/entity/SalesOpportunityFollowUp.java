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
    /** 记录来源：MANUAL=手工录入，WECOM_BOT=企微问句确认落库。 */
    private String source;
    /** 溯源问句 ID（ oportunidades_followup_prompt.id ），手工录入为空。 */
    private Long promptId;
}
