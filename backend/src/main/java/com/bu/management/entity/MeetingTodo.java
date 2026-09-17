package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 会议待办草稿：AI 生成 → 人工编辑 → 转任务/事项。
 */
@Data
@TableName("meeting_todo")
public class MeetingTodo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long meetingId;
    private String title;
    private String description;
    /** AI 提示的负责人人名（≠发言人） */
    private String assigneeHint;
    /** 截止时间原话，如"下周五" */
    private String dueText;
    /** 仅当能由 meeting_date 推算时填写 */
    private LocalDate dueDate;
    private Integer sourceSegmentSeq;
    private Long sourceStartMs;
    private Long sourceEndMs;
    /** 服务端从转写段渲染的原文摘录 */
    private String sourceExcerpt;
    /** DRAFT/CREATED/DISMISSED */
    private String status;
    /** TASK/ISSUE（转化后回填） */
    private String actionType;
    private Long targetId;
    private String targetTitle;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
