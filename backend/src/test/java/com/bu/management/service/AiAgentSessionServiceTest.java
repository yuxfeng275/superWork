package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import com.bu.management.entity.AiAgentSession;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.AiAgentSessionMapper;
import com.bu.management.vo.AiAgentSessionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AiAgentSessionService 消息追加/自动标题单元测试（纯 Jackson + Mockito，无 DB）。
 */
@ExtendWith(MockitoExtension.class)
class AiAgentSessionServiceTest {

    @Mock
    private AiAgentSessionMapper sessionMapper;

    private AiAgentSessionService service;

    @BeforeEach
    void setUp() {
        service = new AiAgentSessionService(sessionMapper, new ObjectMapper());
    }

    private AiAgentSession session(Long id, Long ownerId, String title, String messagesJson) {
        AiAgentSession session = new AiAgentSession();
        session.setId(id);
        session.setOwnerUserId(ownerId);
        session.setTitle(title);
        session.setProvider(AiAgentSessionService.DEFAULT_PROVIDER);
        session.setModel(AiAgentSessionService.DEFAULT_MODEL);
        session.setMessagesJson(messagesJson);
        session.setUpdatedAt(LocalDateTime.of(2026, 9, 3, 10, 0));
        return session;
    }

    @Test
    @DisplayName("appendMessages：messages_json 为 null 时从空数组开始追加")
    void appendsToNullMessagesJson() {
        AiAgentSession existing = session(1L, 7L, "新的对话", null);
        when(sessionMapper.selectById(1L)).thenReturn(existing);

        service.appendMessages(1L,
                "[{\"role\":\"assistant\",\"content\":\"你好\"}]");

        ArgumentCaptor<AiAgentSession> captor = ArgumentCaptor.forClass(AiAgentSession.class);
        verify(sessionMapper).updateById(captor.capture());
        AiAgentSession saved = captor.getValue();
        assertThat(saved.getMessagesJson()).isEqualTo("[{\"role\":\"assistant\",\"content\":\"你好\"}]");
    }

    @Test
    @DisplayName("appendMessages：在既有消息数组后追加，旧→新")
    void appendsAfterExistingMessages() {
        AiAgentSession existing = session(1L, 7L, "任务查询",
                "[{\"role\":\"user\",\"content\":\"查一下我的任务\"}]");
        when(sessionMapper.selectById(1L)).thenReturn(existing);

        service.appendMessages(1L, "[{\"role\":\"assistant\",\"content\":\"共 3 条任务\"}]");

        ArgumentCaptor<AiAgentSession> captor = ArgumentCaptor.forClass(AiAgentSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getMessagesJson())
                .isEqualTo("[{\"role\":\"user\",\"content\":\"查一下我的任务\"},{\"role\":\"assistant\",\"content\":\"共 3 条任务\"}]");
    }
    @Test
    @DisplayName("appendMessages：非法 newMessagesJson 被忽略，不破坏既有消息")
    void ignoresInvalidNewMessages() {
        AiAgentSession existing = session(1L, 7L, "新的对话",
                "[{\"role\":\"user\",\"content\":\"hi\"}]");
        when(sessionMapper.selectById(1L)).thenReturn(existing);

        service.appendMessages(1L, "not-json");

        ArgumentCaptor<AiAgentSession> captor = ArgumentCaptor.forClass(AiAgentSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getMessagesJson()).isEqualTo("[{\"role\":\"user\",\"content\":\"hi\"}]");
    }

    @Test
    @DisplayName("appendMessages：默认标题用首条用户消息自动生成")
    void autoTitlesFromFirstUserMessage() {
        AiAgentSession existing = session(1L, 7L, "新的对话", null);
        when(sessionMapper.selectById(1L)).thenReturn(existing);

        service.appendMessages(1L, "[{\"role\":\"user\",\"content\":\"  帮我看看本月的工时分布  \"}]");

        ArgumentCaptor<AiAgentSession> captor = ArgumentCaptor.forClass(AiAgentSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("帮我看看本月的工时分布");
    }

    @Test
    @DisplayName("appendMessages：标题超 30 字截断")
    void truncatesLongTitle() {
        AiAgentSession existing = session(1L, 7L, "新的对话", null);
        when(sessionMapper.selectById(1L)).thenReturn(existing);

        service.appendMessages(1L, "[{\"role\":\"user\",\"content\":\"123456789012345678901234567890AB\"}]");

        ArgumentCaptor<AiAgentSession> captor = ArgumentCaptor.forClass(AiAgentSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).hasSize(30);
    }

    @Test
    @DisplayName("appendMessages：内容片段取首个 text 部分作为标题")
    void titlesFromFirstTextPart() {
        AiAgentSession existing = session(1L, 7L, "新的对话", null);
        when(sessionMapper.selectById(1L)).thenReturn(existing);

        service.appendMessages(1L, "[{\"role\":\"user\",\"content\":"
                + "[{\"type\":\"thinking\",\"thinking\":\"嗯\"},{\"type\":\"text\",\"text\":\"项目进度如何\"}]}]");

        ArgumentCaptor<AiAgentSession> captor = ArgumentCaptor.forClass(AiAgentSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("项目进度如何");
    }

    @Test
    @DisplayName("已自定义标题不被自动覆盖")
    void keepsCustomTitle() {
        AiAgentSession existing = session(1L, 7L, "我的会话", null);
        when(sessionMapper.selectById(1L)).thenReturn(existing);

        service.appendMessages(1L, "[{\"role\":\"user\",\"content\":\"新消息\"}]");

        ArgumentCaptor<AiAgentSession> captor = ArgumentCaptor.forClass(AiAgentSession.class);
        verify(sessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("我的会话");
    }

    @Test
    @DisplayName("非归属人访问抛 ResourceNotFoundException")
    void rejectsNonOwner() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 7L, "新的对话", null));

        assertThatThrownBy(() -> service.get(99L, 1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("create：未传 provider/model 时默认 zhipu/glm-5.3")
    void createDefaultsProviderAndModel() {
        service.create(7L, null, null, null);

        ArgumentCaptor<AiAgentSession> captor = ArgumentCaptor.forClass(AiAgentSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getProvider()).isEqualTo("zhipu");
        assertThat(captor.getValue().getModel()).isEqualTo("glm-5.3");
        assertThat(captor.getValue().getTitle()).isEqualTo("新的对话");
    }

    @Test
    @DisplayName("get 返回解析后的消息数组（ArrayNode 透传）")
    void getParsesMessages() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, 7L, "新的对话",
                "[{\"role\":\"user\",\"content\":\"hello\",\"timestamp\":1}]"));

        AiAgentSessionView view = service.get(7L, 1L);

        assertThat(view.messages()).isInstanceOf(com.fasterxml.jackson.databind.node.ArrayNode.class);
        assertThat(((com.fasterxml.jackson.databind.node.ArrayNode) view.messages())).hasSize(1);
    }

}
