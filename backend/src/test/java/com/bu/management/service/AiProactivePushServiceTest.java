package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.entity.AiProactivePush;
import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.integration.WeComClient;
import com.bu.management.mapper.AiProactivePushMapper;
import com.bu.management.mapper.EmailWeComMappingMapper;
import java.time.LocalDate;
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
 * 站外到达：不登录系统时，每天一条企微消息；无建议/已推送/未绑定一律不发。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiProactivePushServiceTest {

    @Mock private AiProactivePushMapper pushMapper;
    @Mock private EmailWeComMappingMapper mappingMapper;
    @Mock private AiProactiveService proactiveService;
    @Mock private WeComClient weComClient;

    private AiProactivePushService service;

    @BeforeEach
    void setUp() {
        service = new AiProactivePushService(pushMapper, mappingMapper, proactiveService, weComClient);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "publicBaseUrl", "https://bu.example.com");
        when(pushMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(mappingMapper.selectOne(any(Wrapper.class))).thenReturn(mapping("wx-user-1", 1));
        when(proactiveService.list(any())).thenReturn(List.of(
                suggestion(1L, "本周周报还没有草稿", "周五 17:00 自动生成前可先起一版",
                        "用 weekly_report_facts 取本周事实后起草周报"),
                suggestion(2L, "2 个大事儿本周还没更新进展", "皇家会员体系、飞鹤接口改造",
                        "逐项起草本周进展")));
    }

    @Test
    void pushesOneWecomMessageWithDeepLink() {
        AiProactivePushService.PushResult result = service.pushToday(7L);

        assertThat(result.sent()).isTrue();
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(weComClient).pushText(eq("wx-user-1"), content.capture());
        assertThat(content.getValue())
                .contains("今天最值得先做的是")
                .contains("https://bu.example.com/ai-assistant?prefill=")
                .contains("1. 本周周报还没有草稿")
                .contains("2. 2 个大事儿本周还没更新进展")
                .contains("/ai-assistant?prefill=");

        ArgumentCaptor<AiProactivePush> row = ArgumentCaptor.forClass(AiProactivePush.class);
        verify(pushMapper).insert(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(AiProactivePush.STATUS_SENT);
        assertThat(row.getValue().getSuggestionIds()).isEqualTo("1,2");
        assertThat(row.getValue().getPushedOn()).isEqualTo(LocalDate.now());
    }

    @Test
    void skipsWhenAlreadyPushedToday() {
        when(pushMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        AiProactivePushService.PushResult result = service.pushToday(7L);

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).contains("今天已推送");
        verify(weComClient, never()).pushText(anyString(), anyString());
    }

    @Test
    void skipsWhenUserHasNoWecomBinding() {
        when(mappingMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        AiProactivePushService.PushResult result = service.pushToday(7L);

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).contains("未绑定企业微信");
        verify(weComClient, never()).pushText(anyString(), anyString());
    }

    @Test
    void skipsWhenPublicBaseUrlMissingSoNoDeadLinkIsSent() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publicBaseUrl", "");

        AiProactivePushService.PushResult result = service.pushToday(7L);

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).contains("app.public-base-url");
        verify(weComClient, never()).pushText(anyString(), anyString());
    }

    @Test
    void skipsWhenNothingToSuggest() {
        when(proactiveService.list(any())).thenReturn(List.of());

        AiProactivePushService.PushResult result = service.pushToday(7L);

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).contains("没有需要主动提醒");
        verify(pushMapper, never()).insert(any(AiProactivePush.class));
    }

    @Test
    void recordsFailureInsteadOfThrowing() {
        doThrow(new IllegalStateException("企业微信推送失败(81013)")).when(weComClient)
                .pushText(anyString(), anyString());

        AiProactivePushService.PushResult result = service.pushToday(7L);

        assertThat(result.sent()).isFalse();
        ArgumentCaptor<AiProactivePush> row = ArgumentCaptor.forClass(AiProactivePush.class);
        verify(pushMapper).insert(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(AiProactivePush.STATUS_FAILED);
        assertThat(row.getValue().getReason()).contains("81013");
    }

    private EmailWeComMapping mapping(String wecomUserId, int enabled) {
        EmailWeComMapping mapping = new EmailWeComMapping();
        mapping.setOwnerUserId(7L);
        mapping.setWecomUserId(wecomUserId);
        mapping.setEnabled(enabled);
        return mapping;
    }

    private AiProactiveService.Suggestion suggestion(Long id, String title, String body, String prompt) {
        return new AiProactiveService.Suggestion(id, "weekly_report", title, body, prompt,
                "/weekly-report", 90);
    }
}
