package com.bu.management.orchestration;

import java.util.Set;

/**
 * 节点状态与合法迁移。
 *
 * <p>关键约定：
 * <ul>
 *   <li>{@code PENDING → RUNNING} 不允许——必须先由依赖满足推进到 {@code READY}，
 *       否则「为什么这个节点能跑」在事后无从解释；</li>
 *   <li>{@code UNKNOWN} 是执行器失联或「已提交、结果未落」的落点，只能靠证据核对后
 *       回到 {@code READY}（重试）或 {@code FAILED}（放弃）；</li>
 *   <li>{@code SUCCEEDED → READY} 只用于 gate 打回（rework）：上游产物按 attempt 冻结保留。</li>
 * </ul>
 *
 * @author BU Team
 * @since 2026-11-21
 */
public enum NodeStatus {

    PENDING,
    READY,
    RUNNING,
    WAITING_GATE,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    CANCELLED;

    private Set<NodeStatus> allowedFrom() {
        return switch (this) {
            case PENDING -> Set.of();
            case READY -> Set.of(PENDING, FAILED, UNKNOWN, SUCCEEDED);
            case RUNNING -> Set.of(READY);
            case WAITING_GATE -> Set.of(RUNNING);
            case SUCCEEDED -> Set.of(RUNNING, WAITING_GATE);
            // READY 也算：依赖满足后可能被前置检查直接判失败（例如项目没有事实）
            case FAILED -> Set.of(READY, RUNNING, UNKNOWN, WAITING_GATE);
            case UNKNOWN -> Set.of(RUNNING);
            case CANCELLED -> Set.of(PENDING, READY, RUNNING, WAITING_GATE, FAILED, UNKNOWN);
        };
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** 是否已「跑完并成功」——DAG 推进只看这一个条件。 */
    public boolean isSucceeded() {
        return this == SUCCEEDED;
    }

    public boolean reachableFrom(NodeStatus from) {
        if (from == this) {
            return true;
        }
        return from != null && allowedFrom().contains(from);
    }

    public void requireReachableFrom(NodeStatus from) {
        if (!reachableFrom(from)) {
            throw new IllegalStateException("非法的节点状态迁移：" + from + " → " + this);
        }
    }
}
