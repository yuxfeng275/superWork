package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiProactiveEvent;
import com.bu.management.mapper.AiProactiveEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 事件入口的三个契约：先落事件再刷新建议、同一用户 10 分钟内只刷一次、
 * 事件写入失败不得影响业务调用方。
 */
@ExtendWith(MockitoExtension.class)
class AiProactiveEventServiceTest {

    @Mock
    private AiProactiveEventMapper eventMapper;

    @Mock
    private AiProactiveService proactiveService;

    private AiProactiveEventService service;

    @BeforeEach
    void setUp() {
        service = new AiProactiveEventService(eventMapper, proactiveService);
    }

    @Test
    @DisplayName("记录事件并触发一次扫描")
    void recordsEventAndRefreshes() {
        service.record(7L, AiProactiveEvent.KIND_EMAIL_SYNCED, "3");

        ArgumentCaptor<AiProactiveEvent> event = ArgumentCaptor.forClass(AiProactiveEvent.class);
        verify(eventMapper).insert(event.capture());
        assertThat(event.getValue().getUserId()).isEqualTo(7L);
        assertThat(event.getValue().getKind()).isEqualTo(AiProactiveEvent.KIND_EMAIL_SYNCED);
        assertThat(event.getValue().getRefId()).isEqualTo("3");
        assertThat(event.getValue().getCreatedAt()).isNotNull();
        verify(proactiveService).refreshNow(7L);
    }

    @Test
    @DisplayName("10 分钟内重复事件：照记事件，但只扫一次")
    void refreshIsThrottled() {
        service.record(7L, AiProactiveEvent.KIND_EMAIL_SYNCED, "1");
        service.record(7L, AiProactiveEvent.KIND_EMAIL_SYNCED, "2");

        verify(eventMapper, times(2)).insert(any(AiProactiveEvent.class));
        verify(proactiveService, times(1)).refreshNow(7L);
    }

    @Test
    @DisplayName("无归属人的系统事件：直接忽略")
    void nullUserIsIgnored() {
        service.record(null, AiProactiveEvent.KIND_MEETING_MINUTES, "9");

        verify(eventMapper, never()).insert(any(AiProactiveEvent.class));
        verify(proactiveService, never()).refreshNow(any());
    }

    @Test
    @DisplayName("事件落库失败不抛出：业务调用方不受影响")
    void insertFailureDoesNotThrow() {
        when(eventMapper.insert(any(AiProactiveEvent.class)))
                .thenThrow(new IllegalStateException("db down"));

        service.record(7L, AiProactiveEvent.KIND_WEEKLY_REPORT_FAILED, "5");

        verify(proactiveService).refreshNow(7L);
    }
}
