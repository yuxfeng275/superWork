package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("sales_opportunity")
public class SalesOpportunity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String customer;
    private String type;
    private String status;
    private BigDecimal amount;
    private BigDecimal quotedAmount;
    private Integer quotationCount;
    private LocalDateTime lastQuotationAt;
    private Long wonQuotationId;
    private BigDecimal grossMarginRate;
    private String owner;
    private String businessLine;
    private String nextFollowUp;
    private LocalDateTime createdAt;
    private Integer probability;
    private LocalDate expectedClose;
    private String source;
    private String note;
    /** 结构化下次跟进时间（优先于 nextFollowUp 文本）。 */
    private LocalDateTime nextFollowUpAt;
    /** 跟进状态：PENDING / DONE（本轮待跟进是否已闭环）。 */
    private String followUpState;
    /** 最后一次跟进时间（跟进记录落库时回写）。 */
    private LocalDateTime lastFollowUpAt;
    /** 负责人用户 ID；姓名匹配不上时为空，此时不做企微推送。 */
    private Long ownerUserId;
    /** 是否静音提醒：0=正常提醒，1=用户已手动静音。 */
    private Integer remindMuted;
}
