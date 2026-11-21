package com.bu.management.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 执行器插槽的契约：同一条请求换执行器，编排层看到的事件与终态一致。
 *
 * <p>这条测试就是「切换执行器不改业务代码」的可执行证明：两个实现收到同一个
 * {@link AgentExecutor.ExecRequest}，产出同样形状的 sink 事件。
 */
class ExecutorContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("cli 与 sidecar 对同一请求给出同形状事件与终态")
    void bothExecutorsExposeSameContract() throws Exception {
        HttpServer server = sseServer();
        try {
            CommandExecutor cli = new CommandExecutor();
            ReflectionTestUtils.setField(cli, "timeoutSeconds", 10L);
            SidecarExecutor sidecar = new SidecarExecutor(objectMapper);
            ReflectionTestUtils.setField(sidecar, "baseUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(sidecar, "token", "");
            ReflectionTestUtils.setField(sidecar, "defaultProvider", "deepseek");
            ReflectionTestUtils.setField(sidecar, "defaultModel", "deepseek-chat");
            ReflectionTestUtils.setField(sidecar, "timeoutSeconds", 10L);

            ExecutorRegistry registry = new ExecutorRegistry(List.of(cli, sidecar));
            assertThat(registry.supports("cli")).isTrue();
            assertThat(registry.supports("sidecar")).isTrue();

            AgentExecutor.ExecRequest cliReq = request(List.of("/bin/sh", "-c", "echo 结论一"));
            AgentExecutor.ExecRequest sidecarReq = request(null);

            Recorder cliSink = new Recorder();
            Recorder sidecarSink = new Recorder();

            AgentExecutor.ExecOutcome cliOutcome = registry.require("cli")
                    .start(cliReq, cliSink).await(Duration.ofSeconds(10));
            AgentExecutor.ExecOutcome sidecarOutcome = registry.require("sidecar")
                    .start(sidecarReq, sidecarSink).await(Duration.ofSeconds(10));

            assertThat(cliOutcome).isEqualTo(AgentExecutor.ExecOutcome.OK);
            assertThat(sidecarOutcome).isEqualTo(AgentExecutor.ExecOutcome.OK);
            assertThat(cliSink.texts).anySatisfy(t -> assertThat(t).contains("结论一"));
            assertThat(sidecarSink.texts).anySatisfy(t -> assertThat(t).contains("结论二"));
            // 两边都只报一次终态，编排层不需要知道执行器是谁
            assertThat(cliSink.finished.get()).isEqualTo(AgentExecutor.ExecOutcome.OK);
            assertThat(sidecarSink.finished.get()).isEqualTo(AgentExecutor.ExecOutcome.OK);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("未注册的执行器：明确报错，不退化成默认执行器")
    void unknownExecutorIsAnError() {
        ExecutorRegistry registry = new ExecutorRegistry(List.of(new CommandExecutor()));
        assertThat(registry.supports("dsh-acp")).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.require("dsh-acp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册的执行器");
    }

    private AgentExecutor.ExecRequest request(List<String> command) {
        return new AgentExecutor.ExecRequest("run-1", "node-1", 0, 7L, 1L,
                "你是夜间助手", "写一条结论", null, command, Map.of());
    }

    /** 假 sidecar：吐两条 SSE 事件就关流。 */
    private HttpServer sseServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/runs", exchange -> {
            String body = "event: message_delta\n"
                    + "data: {\"delta\":{\"type\":\"text_delta\",\"text\":\"结论二\"}}\n\n"
                    + "event: run_end\n"
                    + "data: {}\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    /** 收集 sink 事件，便于跨执行器比较。 */
    private static final class Recorder implements AgentExecutor.ExecSink {
        final List<String> texts = new ArrayList<>();
        final AtomicReference<AgentExecutor.ExecOutcome> finished = new AtomicReference<>();

        @Override
        public void onSession(String executorSessionId) {
        }

        @Override
        public void onText(String delta) {
            texts.add(delta);
        }

        @Override
        public void onThinking(String delta) {
        }

        @Override
        public void onToolCall(String callId, String toolName, String argsJson) {
        }

        @Override
        public void onToolResult(String callId, String resultJson, boolean isError) {
        }

        @Override
        public void onFinish(AgentExecutor.ExecOutcome outcome, String summary) {
            finished.compareAndSet(null, outcome);
        }
    }
}
