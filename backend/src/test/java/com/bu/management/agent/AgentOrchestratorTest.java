package com.bu.management.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityDescriptor;
import com.bu.management.capability.CapabilityGateway;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityRegistry;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.integration.DigestLlmClient;
import com.bu.management.service.AiCallRecorder;
import com.bu.management.service.PromptRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 编排器骨架的行为契约：开关、计划校验、逐步执行、失败即停、不抛异常。
 *
 * <p>全部走 Mockito：这一步只验证编排逻辑，不碰真实网关 / 数据库 / 模型。
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    private static final CapabilityContext CTX = CapabilityContext.agent(7L, "run-1", false);

    @Mock
    private CapabilityRegistry registry;

    @Mock
    private CapabilityGateway gateway;

    @Mock
    private AiCallRecorder aiCallRecorder;

    @Mock
    private PromptRegistry promptRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AgentOrchestrator(registry, gateway, aiCallRecorder, promptRegistry,
                objectMapper);
        // 开关是 @Value 字段注入，测试里直接改：其余用例都需要打开
        ReflectionTestUtils.setField(orchestrator, "enabled", true);
    }

    @Test
    @DisplayName("计划 2 步全部成功：按顺序执行、ok=true、观察文本带进下一步")
    void executesPlanInOrder() throws Exception {
        JsonNode planJson = objectMapper.readTree("""
                {"steps":[
                  {"capabilityId":"demo.alpha","input":{"q":"1"},"reason":"先查"},
                  {"capabilityId":"demo.beta","reason":"再查"}
                ]}
                """);
        when(promptRegistry.get(anyString(), anyString())).thenReturn("planner");
        when(aiCallRecorder.call(any(), anyString(), anyString(), any()))
                .thenReturn(new DigestLlmClient.ChatResult(planJson, "mock", "mock-model"));
        when(gateway.invoke(eq("demo.alpha"), any(), any()))
                .thenReturn(CapabilityResult.ok("{\"value\":1}"));
        when(gateway.invoke(eq("demo.beta"), any(), any()))
                .thenReturn(CapabilityResult.ok("{\"value\":2}"));

        AgentRunResult result = orchestrator.run("把 A 和 B 都查一下", CTX);

        assertThat(result.ok()).isTrue();
        assertThat(result.steps()).extracting(AgentRunResult.StepResult::capabilityId)
                .containsExactly("demo.alpha", "demo.beta");
        assertThat(result.steps()).extracting(AgentRunResult.StepResult::index)
                .containsExactly(0, 1);
        assertThat(result.summary()).contains("执行 2 步，成功 2 步");
        verify(gateway, times(2)).invoke(any(), any(), any());

        InOrder order = inOrder(gateway);
        order.verify(gateway).invoke(eq("demo.alpha"), any(), any());
        order.verify(gateway).invoke(eq("demo.beta"), any(), any());

        // 第二步入参带上了第一步的观察文本
        ArgumentCaptor<JsonNode> secondInput = ArgumentCaptor.forClass(JsonNode.class);
        verify(gateway).invoke(eq("demo.beta"), secondInput.capture(), any());
        assertThat(secondInput.getValue().path("_observation").asText()).contains("demo.alpha");
    }

    @Test
    @DisplayName("计划里含不存在的能力：strictCheck 抛异常，让 LLM 换候选")
    void strictCheckRejectsUnknownCapability() throws Exception {
        JsonNode planJson = objectMapper.readTree(
                "{\"steps\":[{\"capabilityId\":\"demo.ghost\"}]}");
        when(promptRegistry.get(anyString(), anyString())).thenReturn("planner");
        when(aiCallRecorder.call(any(), anyString(), anyString(), any()))
                .thenReturn(new DigestLlmClient.ChatResult(planJson, "mock", "mock-model"));

        orchestrator.run("随便问问", CTX);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<JsonNode>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(aiCallRecorder).call(any(), anyString(), anyString(), captor.capture());
        assertThatThrownBy(() -> captor.getValue().accept(planJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo.ghost");
    }

    @Test
    @DisplayName("所有候选模型都不合格：返回 ok=false，summary 含非法能力 id")
    void planningFailureReturnsNotOk() {
        when(promptRegistry.get(anyString(), anyString())).thenReturn("planner");
        when(aiCallRecorder.call(any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException(
                        "所有摘要模型均失败：mock/m1 计划引用了未注册的能力：demo.ghost"));

        AgentRunResult result = orchestrator.run("随便问问", CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("demo.ghost");
        assertThat(result.steps()).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("第一步失败：立即终止，不执行第二步")
    void stopsAfterFirstFailure() throws Exception {
        JsonNode planJson = objectMapper.readTree("""
                {"steps":[
                  {"capabilityId":"demo.write","input":{"v":1}},
                  {"capabilityId":"demo.read"}
                ]}
                """);
        when(promptRegistry.get(anyString(), anyString())).thenReturn("planner");
        when(aiCallRecorder.call(any(), anyString(), anyString(), any()))
                .thenReturn(new DigestLlmClient.ChatResult(planJson, "mock", "mock-model"));
        when(registry.find("demo.write"))
                .thenReturn(Optional.of(descriptor("demo.write", CapabilityRisk.WRITE)));
        when(gateway.invoke(eq("demo.write"), any(), any()))
                .thenReturn(CapabilityResult.fail("GATE_DENIED", "门禁拒绝"));

        AgentRunResult result = orchestrator.run("写一条记录", CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).errorCode()).isEqualTo("GATE_DENIED");
        assertThat(result.summary()).contains("demo.write").contains("写操作");
        verify(gateway, times(1)).invoke(any(), any(), any());
    }

    @Test
    @DisplayName("灰度入口：名单为空/未命中一律拒绝，命中且开关开才放行")
    void grayAllowListIsFailClosed() {
        // setUp 里 enabled=true，但灰度名单为空 → 谁都不放行
        assertThat(orchestrator.allow(7L)).isFalse();
        assertThat(orchestrator.allow(null)).isFalse();

        ReflectionTestUtils.setField(orchestrator, "grayUserIds", "9, 7");
        assertThat(orchestrator.allow(7L)).isTrue();
        assertThat(orchestrator.allow(8L)).isFalse();

        ReflectionTestUtils.setField(orchestrator, "enabled", false);
        assertThat(orchestrator.allow(7L)).isFalse();
    }

    @Test
    @DisplayName("开关关闭：直接返回未启用，不调 LLM 也不调网关")
    void disabledSwitchSkipsEverything() {
        ReflectionTestUtils.setField(orchestrator, "enabled", false);

        AgentRunResult result = orchestrator.run("随便问问", CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("ai.agent.enabled=false");
        verifyNoInteractions(aiCallRecorder, gateway, promptRegistry);
    }

    /** 构造一个真实描述符（record 无副作用，不需要 mock）。 */
    private CapabilityDescriptor descriptor(String id, CapabilityRisk risk) {
        return new CapabilityDescriptor(id, CapabilityKind.TOOL, risk, id + " 标题", "测试用",
                objectMapper.createObjectNode(), 1000, true, "bean-" + id);
    }
}
