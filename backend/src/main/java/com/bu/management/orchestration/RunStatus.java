package com.bu.management.orchestration;

import java.util.List;
import java.util.Set;

/**
 * run 的状态与合法迁移。
 *
 * <p>把迁移写成显式集合而不是「随便改」：夜间任务的状态一旦被写错，
 * 回顾时就无法判断到底是没跑、跑一半、还是在等人，指标也全部失真。
 *
 * @author BU Team
 * @since 2026-11-21
 */
public enum RunStatus {

    PENDING,
    RUNNING,
    WAITING_HUMAN,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED;

    private static final List<RunStatus> TERMINAL = List.of(SUCCEEDED, PARTIAL, FAILED, CANCELLED);

    private Set<RunStatus> allowedFrom() {
        return switch (this) {
            case PENDING -> Set.of();
            case RUNNING -> Set.of(PENDING, WAITING_HUMAN);
            case WAITING_HUMAN -> Set.of(RUNNING);
            case SUCCEEDED, PARTIAL, FAILED -> Set.of(RUNNING, WAITING_HUMAN);
            case CANCELLED -> Set.of(PENDING, RUNNING, WAITING_HUMAN);
        };
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 能否从 {@code from} 迁到本状态；自己到自己视为幂等重写，允许。 */
    public boolean reachableFrom(RunStatus from) {
        if (from == this) {
            return true;
        }
        return from != null && allowedFrom().contains(from);
    }

    /** 拒绝非法迁移，抛出的消息要能直接进日志。 */
    public void requireReachableFrom(RunStatus from) {
        if (!reachableFrom(from)) {
            throw new IllegalStateException("非法的 run 状态迁移：" + from + " → " + this);
        }
    }
}
