package com.bu.management.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.Connector;
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
        Connector connector = new Connector();
        connector.setCode("typesafe");
        connector.setName("TypeSafe Jev");
        connector.setBaseUrl("https://api.typesafe.ai");
        when(registryService.postJson(eq(connector), eq("/v1/systemone"), any(), eq("sk-jev")))
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

        JevClient.Evaluation evaluation = client.evaluate(connector, "sk-jev", "jev-latest",
                Map.of("user_message", "搜一下验收邮件"), questions);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(registryService).postJson(eq(connector), eq("/v1/systemone"), captor.capture(), eq("sk-jev"));
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
        Connector connector = new Connector();
        connector.setName("TypeSafe Jev");
        when(registryService.postJson(any(), any(), any(), any()))
                .thenReturn(mapper.createObjectNode());

        assertThatThrownBy(() -> client.evaluate(connector, "sk", "jev-latest", "hello", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("answers");
    }
}
