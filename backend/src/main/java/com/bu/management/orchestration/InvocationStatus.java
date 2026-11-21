package com.bu.management.orchestration;

/**
 * 工具调用的生命周期。
 *
 * <p>语义核心：{@link #STARTED} 是「已经决定要做、可能已经产生副作用」的唯一证据。
 * 进程崩在 STARTED 与终态之间时，只能通过核对业务侧真实状态来判断结果，
 * 不允许直接重试——那正是「业务已写、结果未落」导致重复写入的原因。
 *
 * @author BU Team
 * @since 2026-11-21
 */
public enum InvocationStatus {

    STARTED,
    SUCCEEDED,
    FAILED,
    /** 有前置记录但拿不到结果：必须核对，不可直接重试。 */
    UNKNOWN,
    /** 命中幂等键，复用前一次的结果，不再执行。 */
    REPLAYED;

    public boolean isTerminal() {
        return this != STARTED;
    }
}
