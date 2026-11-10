package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 统一 AI 任务队列的一行：一次「跑得慢、可能失败、要重试」的 AI 动作。
 *
 * <p>队列的核心语义是<b>抢夺式执行</b>：调度器先原子地把行从 {@link #STATUS_PENDING} 改成
 * {@link #STATUS_RUNNING}（{@code attempt + 1}），只有改成功的那个执行者才真正调 handler。
 * 所以多实例部署时同一行不会被跑两遍，也不会因为某个实例重启把任务丢掉。
 *
 * <p>重试：handler 抛异常时按 {@code 30s * 2^(attempt-1)}（上限 30 分钟）后移 {@code next_run_at}
 * 回 PENDING，{@code attempt} 用尽（{@code attempt >= max_attempt}）才置 {@link #STATUS_FAILED}。
 *
 * @author BU Team
 * @since 2026-12-10
 */
@Data
@TableName("ai_task")
public class AiTask {

    /** 待执行（到期即可被抢）。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已被某个执行者抢走，正在跑 handler。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 执行成功，{@code result} 可用。 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 重试次数用尽或不可重试的失败。 */
    public static final String STATUS_FAILED = "FAILED";
    /** 人工取消，调度器不再扫。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型，对应一个 {@code AiTaskHandler#type()}。 */
    private String type;

    /** 状态：PENDING / RUNNING / SUCCESS / FAILED / CANCELLED。 */
    private String status;

    /** 已执行次数（抢夺时 +1）。 */
    private Integer attempt;

    /** 最大执行次数，用尽后置 FAILED。 */
    private Integer maxAttempt;

    /** 最早可执行时间，失败后按指数退避后移。 */
    private LocalDateTime nextRunAt;

    /** 入参 JSON，交给 handler 自己解析。 */
    private String payload;

    /** 成功时 handler 返回的结果 JSON。 */
    private String result;

    /** 最后一次失败原因（截 500 字）。 */
    private String error;

    /** 触发人用户 ID。 */
    private Long ownerUserId;

    /** 最后一次被抢走的时间，用于排查僵死行。 */
    private LocalDateTime lockedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
