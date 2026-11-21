package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 一次流程实例（夜间批、同步巡检、M2 队列…）。
 *
 * <p>{@code (kind, sop_code, project_id, owner_user_id, biz_date)} 唯一：触发层重复发同一天的任务时，
 * 拿到的是同一个 run，而不是第二条流水账。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Data
@TableName("ai_run")
public class AiRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runNo;

    /** NIGHT_BATCH / SYNC_CHECK / M2_QUEUE / ADHOC */
    private String kind;

    private String sopCode;

    private Integer sopVersion;

    private Long projectId;

    /** 以谁的身份跑：必须是真实成员，不用服务账号 */
    private Long ownerUserId;

    private LocalDate bizDate;

    /** AUTOLOOP / CRON / API / EVENT */
    private String triggerSource;

    private String status;

    /** 租约代次：提交状态时校验，旧 worker 的写入被拒 */
    private Integer epoch;

    private String leaseOwner;

    private LocalDateTime leaseExpiresAt;

    private Integer attempt;

    private String summary;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
