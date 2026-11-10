package com.bu.management.agent;

import java.util.List;

/**
 * 一次任务型执行的结果：总体成败 + 人话汇总 + 逐步明细。
 *
 * <p>刻意不让编排器再调一次 LLM 去「润色」汇总：多一次调用就多一次成本、一次延迟和
 * 一次不确定性，而调用方真正需要的是「哪一步干了什么、成没成」这种可核对的事实。
 * 润色留给上层（对话 / 报告）自己决定。
 *
 * @param ok        整体是否成功：所有步骤都成功且未触发终止条件
 * @param summary   人话汇总，直接可以回给用户或写进日志
 * @param steps     逐步明细，按执行顺序
 * @param latencyMs 编排总耗时（含规划 + 执行）
 * @author BU Team
 * @since 2026-11-21
 */
public record AgentRunResult(boolean ok, String summary, List<StepResult> steps, long latencyMs) {

    public AgentRunResult {
        summary = summary == null ? "" : summary;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * 单步明细。
     *
     * @param index        步骤序号，0 起
     * @param capabilityId 能力编码
     * @param ok           该步是否成功
     * @param dataJson     成功时的结果 JSON（失败为 null，避免把半截数据当结果用）
     * @param errorCode    失败错误码（成功为 null）
     * @param latencyMs    该步耗时
     */
    public record StepResult(int index, String capabilityId, boolean ok, String dataJson,
                             String errorCode, long latencyMs) {}

    /** 未执行任何步骤的失败结果（开关关闭 / 规划失败 / 异常），步骤列表为空。 */
    public static AgentRunResult failed(String summary, long latencyMs) {
        return new AgentRunResult(false, summary, List.of(), latencyMs);
    }

    /** 成功步数，给上层做「M/N」展示。 */
    public long successCount() {
        return steps.stream().filter(StepResult::ok).count();
    }
}
