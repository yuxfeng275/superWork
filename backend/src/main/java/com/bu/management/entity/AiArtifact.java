package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 制品：夜间 agent 的产出，白天人只做评审。
 *
 * <p>口径（方案 §5.3）：status 在 DRAFT/IN_REVIEW/APPROVED/REJECTED/APPLIED 之间单向流转；
 * evidence 存 project_fact.id 列表——无证据的制品不许进评审队列；
 * APPROVED 采纳后回写一条项目事实，形成「采纳即新事实」的回流。
 * task_id 只作回溯，任务状态唯一来源仍是 agent-autoloop。
 */
@Data
@TableName("ai_artifact")
public class AiArtifact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** 产出者（夜间运行注册的身份） */
    private Long ownerUserId;

    /** agent-autoloop 任务号，回溯用 */
    private String taskId;

    /** PLAN / PATCH / FIX_NOTE / REVIEW_FIX / CHECKLIST */
    private String kind;

    private String title;

    /** 方案正文 / 补丁说明 / diff 摘要 */
    private String content;

    /** JSON：project_fact.id 列表，无证据不允许进评审 */
    private String evidence;

    /** 代码类制品绑定的仓库 */
    private Long repoId;

    /** 夜间产出分支，永不直接改主分支 */
    private String branch;

    /** DRAFT / IN_REVIEW / APPROVED / REJECTED / APPLIED */
    private String status;

    /** 驳回原因（REJECTED 必填） */
    private String rejectReason;

    private Long reviewerId;

    private LocalDateTime decidedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
