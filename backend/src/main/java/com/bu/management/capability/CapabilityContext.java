package com.bu.management.capability;

/**
 * 能力调用上下文：把「谁、在哪个运行里、是否无人值守」带进 Handler。
 *
 * <p>刻意只放标识，不放业务对象——上下文一旦能装任意东西，
 * 半年后就会变成第二个 ServiceLocator。Handler 需要什么自己去注入。
 *
 * @param userId         调用人，系统 / 定时任务触发时为 null
 * @param runId          关联的 AI 运行 ID，Jev 门禁拿它当「用户上文」
 * @param taskId         关联的 ai_task.id，非任务驱动为 null
 * @param unattended     是否无人值守（定时任务 / 夜间批处理）：写操作需更保守
 * @param traceId        链路追踪 ID，透传到远端能力
 * @param idempotencyKey 幂等键，远端 / 队列重放时用来去重
 * @author BU Team
 * @since 2026-11-20
 */
public record CapabilityContext(
        Long userId,
        String runId,
        Long taskId,
        boolean unattended,
        String traceId,
        String idempotencyKey) {

    /**
     * 系统调用上下文：定时任务、启动自检等没有用户在场的场景。
     * unattended 置 true，让写操作走更保守的判断。
     */
    public static CapabilityContext system() {
        return new CapabilityContext(null, null, null, true, null, null);
    }

    /**
     * 助手会话上下文：有明确用户在跟助手对话。
     *
     * @param userId     调用人
     * @param runId      本次 AI 运行 ID，同时作为 traceId 起点（一次运行一条链）
     * @param unattended 是否无人值守：助手自动跑的工具链为 true
     */
    public static CapabilityContext agent(Long userId, String runId, boolean unattended) {
        return new CapabilityContext(userId, runId, null, unattended, runId, null);
    }

    /** 是否有人在场：无人值守时门禁应更保守。 */
    public boolean hasUser() {
        return userId != null && !unattended;
    }

    /** 补上任务 ID，返回新实例（record 不可变，避免共享上下文被就地改写）。 */
    public CapabilityContext withTaskId(Long taskId) {
        return new CapabilityContext(userId, runId, taskId, unattended, traceId, idempotencyKey);
    }

    /** 补上幂等键，返回新实例。 */
    public CapabilityContext withIdempotencyKey(String idempotencyKey) {
        return new CapabilityContext(userId, runId, taskId, unattended, traceId, idempotencyKey);
    }
}
