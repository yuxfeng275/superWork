package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sync_task")
public class SyncTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 任务编码，如 worktime-contract / oa-org */
    private String taskCode;
    private String taskName;
    /** OA / WORKTIME / EXCEL */
    private String sourceSystem;
    /** contract / worklog / cost / org / member */
    private String domain;
    /** 定时表达式，NULL=仅手动触发 */
    private String cron;
    private Integer enabled;
    /** running/success/failed */
    private String lastStatus;
    private LocalDateTime lastRunAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
