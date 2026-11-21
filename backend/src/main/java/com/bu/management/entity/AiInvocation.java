package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 工具调用的前置记录。
 *
 * <p>写入时机固定在「执行器决定调用、副作用尚未发生」之前——这一行是恢复时唯一能区分
 * 「没做过」与「做了但不知道结果」的依据。{@code logical_action_key} 让同一次业务动作
 * 在重试时命中幂等，而不是再写一遍。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Data
@TableName("ai_invocation")
public class AiInvocation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private Long nodeId;

    private Integer attempt;

    /** 执行器给出的调用号 */
    private String callId;

    /** 幂等键：hash(工具 + 规范化参数 + 业务对象) */
    private String logicalActionKey;

    private String toolName;

    private String argsJson;

    /** STARTED / SUCCEEDED / FAILED / UNKNOWN / REPLAYED */
    private String status;

    private String resultJson;

    private String errorCode;

    /** 前置写入时间，必须早于副作用 */
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 回放来源 invocation id */
    private Long replayedFrom;

    /** per-call 审批记录（谁批的、批了什么范围） */
    private String approvalJson;
}
