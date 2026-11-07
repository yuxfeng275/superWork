package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 每人每日晨报：夜间产出汇总后，07:30 每人一条（不群发）。
 * content 为 JSON 字符串，字段口径见 docs/project-agent-loop-plan.md §5.4。
 */
@Data
@TableName("ai_brief")
public class AiBrief {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 0 = 跨项目总晨报 */
    private Long projectId;

    private LocalDate briefDate;

    private String content;

    /** agent-autoloop 任务号，便于 why 回溯 */
    private String sourceTask;

    private LocalDateTime deliveredAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
