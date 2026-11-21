package com.bu.management.executor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 按 {@code executor_kind} 找执行器。
 *
 * <p>找不到就明确报错，而不是悄悄退化成某个默认执行器：夜间任务跑错执行器，
 * 事后从日志里几乎看不出来。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Component
public class ExecutorRegistry {

    private final Map<String, AgentExecutor> byKind;

    public ExecutorRegistry(List<AgentExecutor> executors) {
        this.byKind = executors.stream()
                .collect(Collectors.toMap(e -> e.kind().toLowerCase(Locale.ROOT), e -> e, (a, b) -> a));
        log.info("执行器插槽已装配：{}", byKind.keySet());
    }

    public AgentExecutor require(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("executor_kind 不能为空");
        }
        AgentExecutor executor = byKind.get(kind.strip().toLowerCase(Locale.ROOT));
        if (executor == null) {
            throw new IllegalArgumentException("未注册的执行器：" + kind + "，可用："
                    + String.join("/", byKind.keySet()));
        }
        return executor;
    }

    public boolean supports(String kind) {
        return kind != null && byKind.containsKey(kind.strip().toLowerCase(Locale.ROOT));
    }
}
