package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("data_sync_log")
public class DataSyncLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 关联 sync_task.task_code */
    private String taskCode;
    /** OA / WORKTIME / EXCEL */
    private String sourceSystem;
    /** contract / worklog / cost / org / member */
    private String domain;
    /** 同步范围，如 2026 / 2026-08 / all */
    private String scope;
    /** running/success/failed */
    private String status;
    private Integer totalCount;
    private Integer upsertCount;
    private Integer pendingCount;
    private String message;
    /** schedule/manual/fallback-import */
    private String triggeredBy;
    /** 手动触发人（定时为 NULL） */
    private Long operatorId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
