package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 站内通知已读记录：用户在某天已读某类通知（幂等去重）。
 * 通知内容实时聚合，不落库。
 */
@Data
@TableName("ai_notice_read")
public class AiNoticeRead {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String noticeKind;
    private LocalDate noticeDate;
    private LocalDateTime createdAt;
}
