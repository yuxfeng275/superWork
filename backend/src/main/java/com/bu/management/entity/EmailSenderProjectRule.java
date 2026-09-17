package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮件发件人→项目路由规则：人工纠偏沉淀，分组时优先于 AI 判断。
 */
@Data
@TableName("email_sender_project_rule")
public class EmailSenderProjectRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ownerUserId;
    /** 发件人地址或域名（小写） */
    private String senderPattern;
    private Long projectId;
    /** MANUAL=人工纠偏 / LEARNED=系统沉淀 */
    private String source;
    private Integer hitCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
