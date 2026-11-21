package com.bu.management.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bu.management.entity.AiInvocation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 崩溃点注入：三个窗口各杀一次，验证「恢复后不重复副作用、状态正确」。
 *
 * <p>窗口定义（进程在哪一刻死）：
 * <ol>
 *   <li><b>begin 之前</b>：什么都没记，重试应当正常执行一次；</li>
 *   <li><b>begin 之后、副作用之前</b>：只剩 STARTED，恢复扫描标 UNKNOWN，
 *       必须先核对（这里模拟核对为「未生效」），才允许新 attempt 执行；</li>
 *   <li><b>副作用之后、结果之前</b>：同样只剩 STARTED，核对发现业务已生效，
 *       补写结果并标记 REPLAYED，同一 attempt 的重放不得再写一次。</li>
 * </ol>
 */
class AiInvocationServiceTest {

    private final InMemoryStore store = new InMemoryStore();

    private AiInvocationService service;

    /** 模拟业务侧的真实写入次数：这是唯一能证明「没有重复副作用」的计数器。 */
    private final AtomicInteger businessWrites = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new AiInvocationService(store, new ObjectMapper());
    }

    @Test
    @DisplayName("幂等键：参数顺序无关；attempt 变了就是新动作")
    void logicalKeyIgnoresArgOrderButNotAttempt() {
        String a = service.logicalActionKey("save_artifact", "{\"b\":1,\"a\":2}", 0);
        String b = service.logicalActionKey("save_artifact", "{ \"a\": 2, \"b\": 1 }", 0);
        String c = service.logicalActionKey("save_artifact", "{\"a\":2,\"b\":1}", 1);

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    @DisplayName("崩溃点1：begin 之前死掉 —— 没有任何记录，重试照常执行一次")
    void crashBeforeBeginLeavesNothingBehind() {
        // 进程在这里就死了：begin 没调用，也没有副作用
        assertThat(store.rows()).isEmpty();

        // 重试
        AiInvocationService.BeginResult begin = begin(0, "{\"n\":1}");
        assertThat(begin.replayable()).isFalse();
        writeBusinessOnce();
        service.succeed(begin.invocation().getId(), "{\"ok\":true}");

        assertThat(businessWrites.get()).isEqualTo(1);
        assertThat(store.rows()).hasSize(1);
        assertThat(store.rows().get(0).getStatus()).isEqualTo(InvocationStatus.SUCCEEDED.name());
    }

    @Test
    @DisplayName("崩溃点2：begin 之后、副作用之前死掉 —— 标 UNKNOWN，核对未生效后才允许新 attempt")
    void crashBetweenBeginAndSideEffectIsRecoveredByRecheck() {
        AiInvocationService.BeginResult begin = begin(0, "{\"n\":1}");
        // 进程死在这里：没有调用业务、没有 succeed

        List<AiInvocation> stale = service.markStaleAsUnknown(Duration.ZERO);
        assertThat(stale).hasSize(1);
        assertThat(stale.get(0).getStatus()).isEqualTo(InvocationStatus.UNKNOWN.name());

        // 同一 attempt 想重发：不允许执行（可回放/在途都必须跳过）
        AiInvocationService.BeginResult retrySame = service.begin(call(0, "{\"n\":1}"));
        assertThat(retrySame.replayable() || retrySame.inFlight()).isTrue();
        assertThat(businessWrites.get()).isZero();

        // 核对：业务侧确实没生效 → 标记未生效，然后新 attempt 才执行
        service.resolveUnknownAsNotApplied(begin.invocation().getId(), "业务表无该记录");
        AiInvocationService.BeginResult nextAttempt = begin(1, "{\"n\":1}");
        assertThat(nextAttempt.replayable()).isFalse();
        writeBusinessOnce();
        service.succeed(nextAttempt.invocation().getId(), "{\"ok\":true}");

        assertThat(businessWrites.get()).isEqualTo(1);
        assertThat(service.require(begin.invocation().getId()).getStatus())
                .isEqualTo(InvocationStatus.FAILED.name());
        assertThat(service.require(nextAttempt.invocation().getId()).getStatus())
                .isEqualTo(InvocationStatus.SUCCEEDED.name());
    }

    @Test
    @DisplayName("崩溃点3：副作用之后、结果之前死掉 —— 核对已生效，补结果且不再写第二遍")
    void crashAfterSideEffectDoesNotDoubleWrite() {
        AiInvocationService.BeginResult begin = begin(0, "{\"n\":1}");
        writeBusinessOnce();          // 业务已经写了
        // 进程死在这里：没有 succeed

        List<AiInvocation> stale = service.markStaleAsUnknown(Duration.ZERO);
        assertThat(stale).hasSize(1);

        // 同一 attempt 重放：不得再执行（这是重复写入的防线）
        AiInvocationService.BeginResult replay = service.begin(call(0, "{\"n\":1}"));
        assertThat(replay.replayable() || replay.inFlight()).isTrue();
        assertThat(businessWrites.get()).isEqualTo(1);

        // 核对发现已生效 → 补写结果，状态 REPLAYED
        AiInvocation resolved = service.resolveUnknownAsSucceeded(begin.invocation().getId(),
                "{\"found\":true}");
        assertThat(resolved.getStatus()).isEqualTo(InvocationStatus.REPLAYED.name());
        assertThat(businessWrites.get()).isEqualTo(1);
        assertThat(service.markStaleAsUnknown(Duration.ZERO)).isEmpty();
    }

    @Test
    @DisplayName("重复提交结果：第二次直接报错，不会覆盖第一次")
    void doubleFinishRejected() {
        AiInvocationService.BeginResult begin = begin(0, "{}");
        service.succeed(begin.invocation().getId(), "{\"ok\":true}");

        assertThatThrownBy(() -> service.succeed(begin.invocation().getId(), "{\"ok\":true}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝重复提交");
    }

    @Test
    @DisplayName("恢复扫描只碰 STARTED：已成功的调用不受影响")
    void staleScanOnlyTouchesStarted() {
        AiInvocationService.BeginResult ok = begin(0, "{\"a\":1}");
        service.succeed(ok.invocation().getId(), "{\"ok\":true}");

        assertThat(service.markStaleAsUnknown(Duration.ZERO)).isEmpty();
        assertThat(service.require(ok.invocation().getId()).getStatus())
                .isEqualTo(InvocationStatus.SUCCEEDED.name());
    }

    private AiInvocationService.BeginResult begin(int attempt, String args) {
        return service.begin(call(attempt, args));
    }

    private AiInvocationService.BeginCall call(int attempt, String args) {
        return new AiInvocationService.BeginCall(5L, 11L, attempt, "call-a" + attempt,
                "save_artifact", args);
    }

    private void writeBusinessOnce() {
        businessWrites.incrementAndGet();
    }

    /** 内存实现：只为把三个崩溃点跑出来，不模拟 SQL。 */
    private static final class InMemoryStore implements InvocationStore {

        private final Map<Long, AiInvocation> rows = new HashMap<>();

        private final AtomicLong seq = new AtomicLong();

        List<AiInvocation> rows() {
            return rows.values().stream()
                    .sorted(Comparator.comparing(AiInvocation::getId))
                    .collect(Collectors.toList());
        }

        @Override
        public AiInvocation findByActionKey(Long runId, String logicalActionKey) {
            return rows.values().stream()
                    .filter(r -> runId.equals(r.getRunId())
                            && logicalActionKey.equals(r.getLogicalActionKey()))
                    .findFirst().orElse(null);
        }

        @Override
        public AiInvocation findById(Long id) {
            return rows.get(id);
        }

        @Override
        public void insert(AiInvocation row) {
            long id = seq.incrementAndGet();
            row.setId(id);
            rows.put(id, copy(row));
        }

        @Override
        public int updateIfStatusIn(Long id, Set<String> expected, AiInvocation patch) {
            AiInvocation current = rows.get(id);
            if (current == null || !expected.contains(current.getStatus())) {
                return 0;
            }
            if (patch.getStatus() != null) {
                current.setStatus(patch.getStatus());
            }
            if (patch.getResultJson() != null) {
                current.setResultJson(patch.getResultJson());
            }
            if (patch.getErrorCode() != null) {
                current.setErrorCode(patch.getErrorCode());
            }
            if (patch.getFinishedAt() != null) {
                current.setFinishedAt(patch.getFinishedAt());
            }
            return 1;
        }

        @Override
        public List<AiInvocation> listStartedBefore(LocalDateTime threshold) {
            List<AiInvocation> out = new ArrayList<>();
            for (AiInvocation row : rows.values()) {
                if (InvocationStatus.STARTED.name().equals(row.getStatus())
                        && row.getStartedAt() != null
                        && row.getStartedAt().isBefore(threshold)) {
                    out.add(copy(row));
                }
            }
            return out;
        }

        private static AiInvocation copy(AiInvocation row) {
            AiInvocation c = new AiInvocation();
            c.setId(row.getId());
            c.setRunId(row.getRunId());
            c.setNodeId(row.getNodeId());
            c.setAttempt(row.getAttempt());
            c.setCallId(row.getCallId());
            c.setLogicalActionKey(row.getLogicalActionKey());
            c.setToolName(row.getToolName());
            c.setArgsJson(row.getArgsJson());
            c.setStatus(row.getStatus());
            c.setResultJson(row.getResultJson());
            c.setErrorCode(row.getErrorCode());
            c.setStartedAt(row.getStartedAt());
            c.setFinishedAt(row.getFinishedAt());
            return c;
        }
    }
}
