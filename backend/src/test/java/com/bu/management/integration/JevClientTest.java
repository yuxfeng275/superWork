package com.bu.management.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.service.ConnectorRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JevClientTest {

    @Mock private ConnectorRegistryService registryService;

    private JevClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new JevClient(registryService, mapper);
    }

    @Test
    @DisplayName("evaluate：POST /v1/systemone，解析 Choice 与 confidence")
    @SuppressWarnings("unchecked")
    void evaluatePostsTypedQuestions() {
        when(registryService.postJson(eq("https://api.typesafe.ai"), eq("/v1/systemone"), any(), eq("sk-jev"), eq("TypeSafe Jev")))
                .thenReturn(mapper.createObjectNode()
                        .put("model", "jev-1.13.0")
                        .set("answers", mapper.createObjectNode()
                                .set("intent", mapper.createObjectNode()
                                        .put("type", "choice")
                                        .put("choice", "mail")
                                        .put("confidence", 0.81))));

        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("intent", Map.of(
                "type", "choice",
                "instructions", "What is the user asking about?",
                "criteria", Map.of("mail", "email", "hours", "worklogs")));

        JevClient.Evaluation evaluation = client.evaluate("https://api.typesafe.ai", "sk-jev", "jev-latest",
                Map.of("user_message", "搜一下验收邮件"), questions);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(registryService).postJson(eq("https://api.typesafe.ai"), eq("/v1/systemone"), captor.capture(), eq("sk-jev"), eq("TypeSafe Jev"));
        assertThat(captor.getValue()).containsEntry("model", "jev-latest");
        assertThat(captor.getValue()).containsKey("state");
        assertThat(captor.getValue()).containsKey("questions");
        assertThat(evaluation.model()).isEqualTo("jev-1.13.0");
        assertThat(evaluation.choice("intent")).isEqualTo("mail");
        assertThat(evaluation.confidence("intent")).isEqualTo(0.81);
    }

    @Test
    @DisplayName("evaluate：缺少 answers 时抛业务异常")
    void evaluateRejectsEmptyAnswers() {
        when(registryService.postJson(any(), any(), any(), any(), any()))
                .thenReturn(mapper.createObjectNode());

        assertThatThrownBy(() -> client.evaluate("https://api.typesafe.ai", "sk", "jev-latest", "hello", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("answers");
    }
    @Test
    @DisplayName("evaluate：baseUrl 已含 /v1/systemone 时不重复拼接")
    void evaluateStripsEmbeddedPath() {
        when(registryService.postJson(eq("https://api.typesafe.ai"), eq("/v1/systemone"), any(), eq("sk"), any()))
                .thenReturn(mapper.createObjectNode().set("answers", mapper.createObjectNode()
                        .set("judge", mapper.createObjectNode().put("type", "noul").put("noul", 0.9))));

        client.evaluate("https://api.typesafe.ai/v1/systemone", "sk", "jev-latest", "s", Map.of());

        verify(registryService).postJson(eq("https://api.typesafe.ai"), eq("/v1/systemone"), any(), eq("sk"), any());
    }

    @Test
    @DisplayName("evaluate：proxy 非空时 postJson 携带前代地址")
    void evaluatePassesProxyThrough() {
        when(registryService.postJson(eq("https://api.typesafe.ai"), eq("/v1/systemone"), any(), eq("sk"),
                eq("TypeSafe Jev"), eq("http://mihomo:7890")))
                .thenReturn(mapper.createObjectNode().set("answers", mapper.createObjectNode()
                        .set("judge", mapper.createObjectNode().put("type", "noul").put("noul", 0.9))));

        client.evaluate("https://api.typesafe.ai", "sk", "jev-latest", "s", Map.of(), "http://mihomo:7890");

        verify(registryService).postJson(eq("https://api.typesafe.ai"), eq("/v1/systemone"), any(), eq("sk"),
                eq("TypeSafe Jev"), eq("http://mihomo:7890"));
    }
}
