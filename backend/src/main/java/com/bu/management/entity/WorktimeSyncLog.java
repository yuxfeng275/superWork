package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("worktime_sync_log")
public class WorktimeSyncLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** contract=合同明细/worklog=工时明细/cost=成本分析 */
    private String syncType;
    /** 同步范围，如 2026 / 2026-08 */
    private String scope;
    /** running/success/failed */
    private String status;
    private Integer totalCount;
    private Integer upsertCount;
    private Integer pendingCount;
    private String message;
    /** schedule/manual */
    private String triggeredBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
