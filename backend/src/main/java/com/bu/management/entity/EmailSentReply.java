package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** SMTP 回复发送记录：挂在原邮件会话下，形成往来线程。 */
@Data
@TableName("email_sent_reply")
public class EmailSentReply {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;
    private Long messageId;
    private String toAddress;
    private String subject;
    private String bodyText;
    /** SENT/FAILED */
    private String status;
    private String errorMessage;
    private LocalDateTime sentAt;
}
