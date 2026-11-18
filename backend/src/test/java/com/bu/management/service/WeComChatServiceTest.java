package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.mapper.EmailWeComMappingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 企微自由对话：绑定映射决定身份；未绑定不猜；运行失败有明确原因。
 */
@ExtendWith(MockitoExtension.class)
class WeComChatServiceTest {

    @Mock
    private EmailWeComMappingMapper mappingMapper;

    @Mock
    private AutomationRunService automationRunService;

    private WeComChatService service;

    @BeforeEach
    void setUp() {
        service = new WeComChatService(mappingMapper, automationRunService);
    }

    @Test
    @DisplayName("绑定用户：以 owner 身份跑助手并返回文本")
    void repliesWithBoundUser() {
        EmailWeComMapping mapping = new EmailWeComMapping();
        mapping.setOwnerUserId(7L);
        mapping.setWecomUserId("wx-1");
        mapping.setEnabled(1);
        when(mappingMapper.selectOne(any())).thenReturn(mapping);
        when(automationRunService.runChat(eq(7L), eq("企微对话"), eq("你好")))
                .thenReturn(new AutomationRunService.RunResult(
                        "run-1", 1L, "deepseek", "flash", "你好，我是合拍助手", null, 1));

        WeComChatService.Reply reply = service.reply("wx-1", "你好");

        assertThat(reply.matched()).isTrue();
        assertThat(reply.text()).contains("合拍助手");
    }

    @Test
    @DisplayName("未绑定：不猜身份，返回未绑定")
    void unboundSenderIsRejected() {
        when(mappingMapper.selectOne(any())).thenReturn(null);

        WeComChatService.Reply reply = service.reply("wx-unknown", "你好");

        assertThat(reply.matched()).isFalse();
        assertThat(reply.reason()).contains("未绑定");
        verify(automationRunService, never()).runChat(any(), any(), any());
    }

    @Test
    @DisplayName("助手运行失败：返回失败原因，不抛异常")
    void runFailureIsReported() {
        EmailWeComMapping mapping = new EmailWeComMapping();
        mapping.setOwnerUserId(7L);
        when(mappingMapper.selectOne(any())).thenReturn(mapping);
        when(automationRunService.runChat(eq(7L), eq("企微对话"), eq("你好")))
                .thenReturn(new AutomationRunService.RunResult(
                        "run-1", 1L, "deepseek", "flash", null, "模型全挂了", 0));

        WeComChatService.Reply reply = service.reply("wx-1", "你好");

        assertThat(reply.matched()).isFalse();
        assertThat(reply.reason()).contains("模型全挂了");
    }

    @Test
    @DisplayName("空发送人/空文本：直接拒绝")
    void blankInputIsRejected() {
        assertThat(service.reply("", "hi").matched()).isFalse();
        assertThat(service.reply("wx-1", " ").matched()).isFalse();
    }
}
