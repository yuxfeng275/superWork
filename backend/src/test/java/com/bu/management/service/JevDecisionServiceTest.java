package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.Connector;
import com.bu.management.integration.JevClient;
import com.bu.management.vo.AiAgentToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JevDecisionServiceTest {

    @Mock private AiModelConfigService modelConfigService;
    @Mock private ConnectorRegistryService registryService;
    @Mock private JevClient jevClient;

    private JevDecisionService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new JevDecisionService(modelConfigService, registryService, jevClient);
    }

    @Test
    @DisplayName("未配置决策模型时 fail-open：不调 Jev，不过滤工具")
    void skipWhenDecisionModelMissing() {
        when(modelConfigService.decisionModel()).thenReturn(Optional.empty());

        JevDecisionService.IntentDecision decision = service.classifyIntent("搜一下验收邮件");

        assertThat(decision.ready()).isFalse();
        assertThat(decision.intent()).isEqualTo("other");
        verify(jevClient, never()).evaluate(any(), any(), any(), any(), any());
        List<AiAgentToolDefinition> tools = List.of(
                tool("query_my_worklogs"), tool("search_my_emails"), tool("reply_my_email"));
        assertThat(service.filterTools(decision, tools)).hasSize(3);
    }

    @Test
    @DisplayName("高置信邮件意图：收窄到邮件工具，写操作仍保留")
    void highConfidenceMailNarrowsTools() {
        stubReadyDecisionModel();
        when(jevClient.evaluate(any(), any(), any(), any(), any())).thenReturn(evaluation(
                "mail", 0.88, 0.12));

        JevDecisionService.IntentDecision decision = service.classifyIntent("搜一下最近关于项目验收的邮件");

        assertThat(decision.ready()).isTrue();
        assertThat(decision.intent()).isEqualTo("mail");
        assertThat(decision.writeLikely()).isFalse();
        assertThat(decision.summary()).contains("邮件");
        List<AiAgentToolDefinition> filtered = service.filterTools(decision, List.of(
                tool("query_my_worklogs"),
                tool("search_my_emails"),
                tool("read_my_email"),
                tool("reply_my_email"),
                tool("query_yunxiao_workitems")));
        assertThat(filtered).extracting(AiAgentToolDefinition::name)
                .containsExactly("search_my_emails", "read_my_email", "reply_my_email");
    }

    @Test
    @DisplayName("低置信意图：不过滤工具，避免误伤")
    void lowConfidenceKeepsAllTools() {
        stubReadyDecisionModel();
        when(jevClient.evaluate(any(), any(), any(), any(), any())).thenReturn(evaluation(
                "hours", 0.31, 0.4));

        JevDecisionService.IntentDecision decision = service.classifyIntent("帮我看看");
        List<AiAgentToolDefinition> tools = List.of(
                tool("query_my_worklogs"), tool("search_my_emails"), tool("query_yunxiao_workitems"));
        assertThat(service.filterTools(decision, tools)).hasSize(3);
    }

    @Test
    @DisplayName("写操作门禁：未确认则拦截")
    void writeGateBlocksUnconfirmed() {
        stubReadyDecisionModel();
        when(jevClient.evaluate(any(), any(), eq("jev-latest"), any(), any())).thenReturn(writeEvaluation(
                "recap", 0.9));

        JevDecisionService.WriteGate gate = service.gateWrite(
                "reply_my_email", "帮我回这封验收邮件", "{\"emailId\":1}");

        assertThat(gate.ready()).isTrue();
        assertThat(gate.allow()).isFalse();
        assertThat(gate.action()).isEqualTo("recap");
        assertThat(gate.reason()).contains("确认");
    }

    @Test
    @DisplayName("写操作门禁：高置信 execute 才放行")
    void writeGateAllowsConfirmedExecute() {
        stubReadyDecisionModel();
        when(jevClient.evaluate(any(), any(), any(), any(), any())).thenReturn(writeEvaluation(
                "execute", 0.84));

        JevDecisionService.WriteGate gate = service.gateWrite(
                "reply_my_email", "确认发送刚才那封回复", "{\"emailId\":1}");

        assertThat(gate.allow()).isTrue();
        assertThat(gate.action()).isEqualTo("execute");
    }

    @Test
    @DisplayName("只读工具不走写操作门禁")
    void readToolsSkipWriteGate() {
        JevDecisionService.WriteGate gate = service.gateWrite(
                "search_my_emails", "搜邮件", "{}");
        assertThat(gate.allow()).isTrue();
        assertThat(gate.ready()).isFalse();
        verify(jevClient, never()).evaluate(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Jev 调用失败时 fail-open，不阻断对话")
    void evaluateFailureFailsOpen() {
        stubReadyDecisionModel();
        when(jevClient.evaluate(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("TypeSafe 暂时不可用"));

        JevDecisionService.IntentDecision decision = service.classifyIntent("搜邮件");
        assertThat(decision.ready()).isFalse();
        assertThat(decision.intent()).isEqualTo("other");
    }

    private void stubReadyDecisionModel() {
        Connector connector = new Connector();
        connector.setCode("typesafe");
        connector.setName("TypeSafe Jev");
        connector.setBaseUrl("https://api.typesafe.ai");
        when(modelConfigService.decisionModel()).thenReturn(Optional.of(
                new AiModelConfigService.DecisionModel("typesafe", "https://api.typesafe.ai",
                        "jev-latest", "sk-jev")));
        when(registryService.findByCode("typesafe")).thenReturn(Optional.of(connector));
    }

    private JevClient.Evaluation evaluation(String intent, double confidence, double writeNoul) {
        ObjectNode answers = mapper.createObjectNode();
        answers.set("intent", mapper.createObjectNode()
                .put("type", "choice").put("choice", intent).put("confidence", confidence));
        answers.set("needs_write", mapper.createObjectNode()
                .put("type", "noul").put("noul", writeNoul));
        answers.set("urgency", mapper.createObjectNode()
                .put("type", "score").put("score", 1.2).put("confidence", 0.5));
        return new JevClient.Evaluation("jev-1.13.0", answers);
    }

    private JevClient.Evaluation writeEvaluation(String action, double confidence) {
        ObjectNode answers = mapper.createObjectNode();
        answers.set("action", mapper.createObjectNode()
                .put("type", "choice").put("choice", action).put("confidence", confidence));
        return new JevClient.Evaluation("jev-1.13.0", answers);
    }

    private AiAgentToolDefinition tool(String name) {
        return new AiAgentToolDefinition(name, name, mapper.createObjectNode());
    }
}
