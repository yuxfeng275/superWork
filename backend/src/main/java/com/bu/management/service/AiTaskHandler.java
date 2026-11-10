package com.bu.management.service;

/**
 * AI 任务处理器：一种 {@code ai_task.type} 对应一个实现，由 {@link AiTaskService} 统一调度。
 *
 * <p>用 Spring 装配而不是业务方手工注册：实现类只要标 {@code @Component}，构造注入的
 * {@code List<AiTaskHandler>} 就会把它收进来，再按 {@link #type()} 建成 Map。
 * 因此每个 handler <b>必须</b>声明自己的 type，且 type 全局唯一——重名会在启动期直接失败，
 * 而不是等到线上某条任务悄悄跑错处理器。
 *
 * <p>约定：{@link #handle} 正常返回即视为成功（返回值写 {@code ai_task.result}，可为 null）；
 * 抛异常即视为失败，由调度器按 {@code attempt}/{@code max_attempt} 决定重试还是置 FAILED。
 * 所以「业务上可预期的失败」也请抛异常，不要返回 "error" 字符串。
 *
 * @author BU Team
 * @since 2026-12-10
 */
public interface AiTaskHandler {

    /** 任务类型，必须与 {@code AiTask#type} 一致（如 {@code OPPORTUNITY_COACH}）。 */
    String type();

    /**
     * 执行一次任务。
     *
     * @param payload 入参 JSON（{@code ai_task.payload} 原文，可空）
     * @return 结果 JSON（写回 {@code ai_task.result}，可空）
     * @throws Exception 任何异常都交给调度器按重试策略处理
     */
    String handle(String payload) throws Exception;
}
