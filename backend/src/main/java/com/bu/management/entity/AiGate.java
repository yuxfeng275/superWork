package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 人工闸门：夜间任务停在这里等人放行。
 *
 * <p>三个 {@code requested*} 字段是快照，提交审批时必须与调用方提供的期望值一致，
 * 否则说明这张卡已经过期（节点被重跑过或已被别人批过）——这是「重复审批只生效一次」的实现方式。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Data
@TableName("ai_gate")
public class AiGate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private Long nodeId;

    /** PENDING / APPROVED / REJECTED / APPROVED_RETRY / EXPIRED */
    private String status;

    private Long assigneeUserId;

    private String requestedFromStatus;

    private Integer requestedAttempt;

    private Integer requestedRework;

    private String question;

    private String decision;

    /** 打回目标 node_key */
    private String rejectTo;

    /** decision 制品 id */
    private Long artifactId;

    private Long decidedBy;

    private LocalDateTime decidedAt;

    private LocalDateTime dueAt;

    private LocalDateTime createdAt;
}
