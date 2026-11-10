package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiTask;
import com.bu.management.mapper.AiTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * AI 任务编排的关键分支：抢不到不跑、次数用尽置 FAILED、失败退避回 PENDING、成功写 result、开关关闭不查库。
 *
 * <p>这几条决定「同一任务会不会被执行两遍」「挂了的任务会不会无限重试」「关掉开关是不是真的零开销」，
 * 所以每条都单独钉住，不依赖真实数据库与调度线程。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiTaskServiceTest {

    private static final String TYPE = "OPPORTUNITY_COACH";

    @Mock private AiTaskMapper aiTaskMapper;
    @Mock private SystemConfigService systemConfigService;

    private FakeHandler handler;
    private AiTaskService service;

    @BeforeEach
    void setUp() {
        handler = new FakeHandler();
        // 开关默认开（读不到同样按开），个别用例单独改回 false
        when(systemConfigService.getBoolean("ai-platform", "task.enabled", true)).thenReturn(true);
        service = new AiTaskService(aiTaskMapper, new ObjectMapper(), true, systemConfigService, List.of(handler));
    }

    // ==================== dispatch ====================

    @Test
    void 抢不到执行权就不调处理器() {
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task(1L, AiTask.STATUS_PENDING, 0, 3)));
        // 影响行数 0 = 这行已被其它执行者抢走
        when(aiTaskMapper.update(isNull(), any())).thenReturn(0);

        service.dispatch();

        assertThat(handler.calls).isZero();
        // 抢不到就什么都不该回写，否则会把别人的 RUNNING 行改坏
        verify(aiTaskMapper, never()).updateById(any());
    }

    @Test
    void 重试次数已用尽时置FAILED且不调处理器() {
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task(2L, AiTask.STATUS_PENDING, 3, 3)));
        when(aiTaskMapper.update(isNull(), any())).thenReturn(1);

        service.dispatch();

        assertThat(handler.calls).isZero();
        AiTask patch = captureUpdate();
        assertThat(patch.getStatus()).isEqualTo(AiTask.STATUS_FAILED);
        assertThat(patch.getError()).contains("重试次数已用尽");
    }

    @Test
    void 处理器抛异常时回PENDING并把下次执行时间后移() {
        handler.failure = new IllegalStateException("模型超时");
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task(3L, AiTask.STATUS_PENDING, 0, 3)));
        when(aiTaskMapper.update(isNull(), any())).thenReturn(1);

        LocalDateTime before = LocalDateTime.now();
        service.dispatch();

        assertThat(handler.calls).isEqualTo(1);
        AiTask patch = captureUpdate();
        assertThat(patch.getStatus()).isEqualTo(AiTask.STATUS_PENDING);
        // attempt=1 → 退避 30s
        assertThat(patch.getNextRunAt()).isAfterOrEqualTo(before.plusSeconds(30));
        assertThat(patch.getError()).isEqualTo("模型超时");
    }

    @Test
    void 处理器成功时写SUCCESS和结果() {
        handler.result = "{\"coachId\":9}";
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task(4L, AiTask.STATUS_PENDING, 0, 3)));
        when(aiTaskMapper.update(isNull(), any())).thenReturn(1);

        service.dispatch();

        AiTask patch = captureUpdate();
        assertThat(patch.getStatus()).isEqualTo(AiTask.STATUS_SUCCESS);
        assertThat(patch.getResult()).isEqualTo("{\"coachId\":9}");
    }

    @Test
    void 开关关闭时dispatch不查库也不调处理器() {
        when(systemConfigService.getBoolean("ai-platform", "task.enabled", true)).thenReturn(false);

        service.dispatch();

        verify(aiTaskMapper, never()).selectList(any());
        assertThat(handler.calls).isZero();
    }

    // ==================== 辅助 ====================

    private AiTask captureUpdate() {
        ArgumentCaptor<AiTask> captor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(captor.capture());
        return captor.getValue();
    }

    private AiTask task(Long id, String status, int attempt, int maxAttempt) {
        AiTask task = new AiTask();
        task.setId(id);
        task.setType(TYPE);
        task.setStatus(status);
        task.setAttempt(attempt);
        task.setMaxAttempt(maxAttempt);
        task.setNextRunAt(LocalDateTime.now().minusSeconds(1));
        task.setPayload("{\"opportunityId\":11}");
        task.setOwnerUserId(7L);
        return task;
    }

    /** 可编程的假处理器：默认成功返回固定 JSON，设了 failure 就抛异常。 */
    private static final class FakeHandler implements AiTaskHandler {
        private String result = "{\"ok\":true}";
        private RuntimeException failure;
        private int calls;

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public String handle(String payload) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
