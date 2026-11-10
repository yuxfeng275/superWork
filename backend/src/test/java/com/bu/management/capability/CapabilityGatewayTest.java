package com.bu.management.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiCapabilityCall;
import com.bu.management.mapper.AiCapabilityCallMapper;
import com.bu.management.service.JevDecisionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

/**
 * 网关契约测试：未知能力、异常兜底、超时、写门禁、审计落库、远端开关。
 */
@ExtendWith(MockitoExtension.class)
class CapabilityGatewayTest {

    @Mock private CapabilityRegistry registry;
    @Mock private JevDecisionService jevDecisionService;
    @Mock private AiCapabilityCallMapper callMapper;

    private final ObjectMapper mapper = new ObjectMapper();
    private MockEnvironment environment;
    private CapabilityGateway gateway;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        gateway = new CapabilityGateway(registry, jevDecisionService, callMapper, environment, "");
    }

    /** 把某个能力 id 注册到 mock 注册表里。 */
    private void stubCapability(String id, CapabilityKind kind, CapabilityRisk risk, int timeoutMs,
            CapabilityHandler handler) {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
                id, kind, risk, "标题", "何时用", mapper.createObjectNode(), timeoutMs, true, "testBean");
        when(registry.lookup(id)).thenReturn(Optional.of(new CapabilityRegistry.Registered(descriptor, handler)));
    }

    @Test
    @DisplayName("未知能力：返回 UNKNOWN_CAPABILITY，不执行任何 Handler")
    void unknownCapabilityFails() {
        CapabilityResult result = gateway.invoke("nope.tool", null, CapabilityContext.system());

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNKNOWN_CAPABILITY");
        assertThat(result.errorMessage()).isEqualTo("nope.tool");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("Handler 抛异常：转成 HANDLER_ERROR，不穿透")
    void handlerExceptionBecomesFail() {
        stubCapability("demo.boom", CapabilityKind.TOOL, CapabilityRisk.READ, 1000, (input, ctx) -> {
            throw new IllegalStateException("下游炸了");
        });

        CapabilityResult result = gateway.invoke("demo.boom", null, CapabilityContext.system());

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("HANDLER_ERROR");
        assertThat(result.errorMessage()).contains("下游炸了");
    }

    @Test
    @DisplayName("Handler 超时：转成 TIMEOUT，不阻塞调用方")
    void handlerTimeoutBecomesTimeout() {
        stubCapability("demo.slow", CapabilityKind.TOOL, CapabilityRisk.READ, 50, (input, ctx) -> {
            Thread.sleep(3000);
            return CapabilityResult.ok("{}");
        });

        long start = System.currentTimeMillis();
        CapabilityResult result = gateway.invoke("demo.slow", null, CapabilityContext.system());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TIMEOUT");
        assertThat(elapsed).isLessThan(3000L);
    }

    @Test
    @DisplayName("写能力被 Jev 门禁拒绝：返回 GATE_DENIED，Handler 不执行")
    void writeGateDenied() {
        AtomicBoolean executed = new AtomicBoolean(false);
        stubCapability("demo.write", CapabilityKind.TOOL, CapabilityRisk.WRITE, 1000, (input, ctx) -> {
            executed.set(true);
            return CapabilityResult.ok("{}");
        });
        when(jevDecisionService.gateWrite(eq("demo.write"), anyString(), anyString()))
                .thenReturn(new JevDecisionService.WriteGate(true, false, "recap", "需要先向用户复述"));

        CapabilityResult result = gateway.invoke("demo.write", mapper.createObjectNode(),
                CapabilityContext.agent(7L, "run-1", false));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GATE_DENIED");
        assertThat(result.errorMessage()).contains("复述");
        assertThat(executed).isFalse();
    }

    @Test
    @DisplayName("成功调用：写一行审计，带能力 id / 用户 / runId / 耗时")
    void successWritesAuditRow() {
        stubCapability("demo.echo", CapabilityKind.TOOL, CapabilityRisk.READ, 1000,
                (input, ctx) -> CapabilityResult.ok("{\"echo\":1}"));

        CapabilityResult result = gateway.invoke("demo.echo", null,
                CapabilityContext.agent(9L, "run-9", false));

        assertThat(result.ok()).isTrue();
        assertThat(result.dataJson()).contains("echo");
        ArgumentCaptor<AiCapabilityCall> captor = ArgumentCaptor.forClass(AiCapabilityCall.class);
        verify(callMapper).insert(captor.capture());
        AiCapabilityCall row = captor.getValue();
        assertThat(row.getCapabilityId()).isEqualTo("demo.echo");
        assertThat(row.getUserId()).isEqualTo(9L);
        assertThat(row.getRunId()).isEqualTo("run-9");
        assertThat(row.getOk()).isTrue();
        assertThat(row.getLatencyMs()).isNotNull();
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("审计写库失败不影响调用结果")
    void auditFailureDoesNotBreakResult() {
        stubCapability("demo.echo2", CapabilityKind.TOOL, CapabilityRisk.READ, 1000,
                (input, ctx) -> CapabilityResult.ok("{\"echo\":2}"));
        when(callMapper.insert(any(AiCapabilityCall.class)))
                .thenThrow(new IllegalStateException("数据库挂了"));

        CapabilityResult result = gateway.invoke("demo.echo2", null, CapabilityContext.system());

        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("远端能力开关关闭：返回 REMOTE_DISABLED，不发 HTTP")
    void remoteDisabled() {
        AtomicBoolean executed = new AtomicBoolean(false);
        stubCapability("demo.remote", CapabilityKind.REMOTE, CapabilityRisk.READ, 1000, (input, ctx) -> {
            executed.set(true);
            return CapabilityResult.ok("{}");
        });

        CapabilityResult result = gateway.invoke("demo.remote", mapper.createObjectNode(),
                CapabilityContext.system());

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("REMOTE_DISABLED");
        assertThat(executed).isFalse();
    }

    @Test
    @DisplayName("远端开关打开但未配置地址：仍返回 REMOTE_DISABLED 而不是抛异常")
    void remoteEnabledWithoutBaseUrl() {
        environment.setProperty("ai.platform.remote-capability-enabled", "true");
        stubCapability("demo.remote2", CapabilityKind.REMOTE, CapabilityRisk.READ, 1000,
                (input, ctx) -> CapabilityResult.ok("{}"));

        CapabilityResult result = gateway.invoke("demo.remote2", null, CapabilityContext.system());

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("REMOTE_DISABLED");
        assertThat(result.errorMessage()).contains("remote-capability-base-url");
    }

    @Test
    @DisplayName("远端开关打开且配置地址：连不上时转 REMOTE_ERROR，不穿透")
    void remoteUnreachable() {
        environment.setProperty("ai.platform.remote-capability-enabled", "true");
        CapabilityGateway broken = new CapabilityGateway(registry, jevDecisionService, callMapper,
                environment, "http://127.0.0.1:1");
        stubCapability("demo.remote3", CapabilityKind.REMOTE, CapabilityRisk.READ, 300,
                (input, ctx) -> CapabilityResult.ok("{}"));

        CapabilityResult result = broken.invoke("demo.remote3", null, CapabilityContext.system());

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("REMOTE_ERROR");
    }

    @Test
    @DisplayName("网关开关关闭时仍执行（测试 / 排障逃生口）")
    void gatewayDisabledStillExecutes() {
        environment.setProperty("ai.platform.gateway-enabled", "false");
        stubCapability("demo.echo3", CapabilityKind.TOOL, CapabilityRisk.READ, 1000,
                (input, ctx) -> CapabilityResult.ok("{\"ok\":true}"));

        CapabilityResult result = gateway.invoke("demo.echo3", null, CapabilityContext.system());

        assertThat(result.ok()).isTrue();
        verify(jevDecisionService, never()).gateWrite(any(), any(), any());
    }
}
