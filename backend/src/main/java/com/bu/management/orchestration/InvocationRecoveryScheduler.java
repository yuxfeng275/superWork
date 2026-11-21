package com.bu.management.orchestration;

import com.bu.management.entity.AiInvocation;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 恢复扫描：每 10 分钟把「开始了但一直没有结果」的调用标为 UNKNOWN。
 *
 * <p>它是「结果未知」从隐性变成显性的唯一执行点：不标出来，运行面就只会显示
 * 一个卡在 RUNNING 的节点，没人知道到底写没写。
 *
 * <p>刻意不做的事：不自动重试。未知调用的下一步必须是核对业务真实状态（人或有证据的查询），
 * 自动重试正是重复写入的来源。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvocationRecoveryScheduler {

    /** 超过这个时长仍无结果的调用视为未知。 */
    private static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final AiInvocationService invocationService;

    @Scheduled(cron = "${ai.invocation.recover-cron:0 */10 * * * *}",
            zone = "Asia/Shanghai")
    public void tick() {
        try {
            List<AiInvocation> marked = invocationService.markStaleAsUnknown(STALE_AFTER);
            if (!marked.isEmpty()) {
                String ids = marked.stream()
                        .map(row -> String.valueOf(row.getId()))
                        .reduce((a, b) -> a + "," + b).orElse("");
                log.warn("发现 {} 次结果未知的工具调用（invocationId: {}），需核对业务状态后再决定重试",
                        marked.size(), ids);
            }
        } catch (Exception e) {
            log.error("调用恢复扫描失败：{}", e.getMessage());
        }
    }
}
