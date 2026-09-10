package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 邮件待办/风险转化闭环记录：一条摘要条目（待办/风险/重要/回复）被转化为
 * 任务/事项/大事儿后登记在此；目标完成时置 CLOSED，供摘要划线与去重。
 */
@Data
@TableName("email_action")
public class EmailAction {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;
    private Long messageId;
    /** 摘要条目类别：TODO/RISK/IMPORTANT/REPLY */
    private String itemKind;
    /** 摘要条目标题（转化时快照） */
    private String itemTitle;
    /** 目标类型：TASK/ISSUE/KEY_MATTER */
    private String actionType;
    private Long targetId;
    private String targetTitle;
    /** OPEN/CLOSED */
    private String status;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
}
