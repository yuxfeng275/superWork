package com.bu.management.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 侧车执行器：把一轮交给 {@code ai-sidecar}（{@code POST /v1/runs}，SSE 事件流）。
 *
 * <p>它是业务工具密集型任务的默认执行器：工具目录、权限、回调都在合拍后端，
 * 侧车只负责模型与工具循环。这里只做「协议翻译」——把 SSE 事件翻成
 * {@link AgentExecutor.ExecSink} 的语义事件，不解释业务。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Component
public class SidecarExecutor implements AgentExecutor {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper;

    @Value("${ai.sidecar.base-url:http://ai-sidecar:8787}")
    private String baseUrl;

    @Value("${ai.sidecar.token:}")
    private String token;

    @Value("${ai.sidecar.provider:deepseek}")
    private String defaultProvider;

    @Value("${ai.sidecar.model:deepseek-chat}")
    private String defaultModel;

    @Value("${ai.sidecar.timeout-seconds:600}")
    private long timeoutSeconds;

    public SidecarExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return "sidecar";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.STRUCTURED, Capability.CANCEL);
    }

    @Override
    public ExecHandle start(ExecRequest request, ExecSink sink) {
        CompletableFuture<ExecOutcome> future = CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<Stream<String>> response = http.send(buildRequest(request),
                        HttpResponse.BodyHandlers.ofLines());
                if (response.statusCode() >= 400) {
                    sink.onFinish(ExecOutcome.FAILED, "sidecar HTTP " + response.statusCode());
                    return ExecOutcome.FAILED;
                }
                return consume(response.body(), sink);
            } catch (Exception e) {
                log.warn("sidecar 执行失败 runId={}：{}", request.runId(), e.getMessage());
                sink.onFinish(ExecOutcome.FAILED, e.getMessage());
                return ExecOutcome.FAILED;
            }
        });

        return new ExecHandle() {
            @Override
            public String sessionId() {
                return request.resumeSessionId();
            }

            @Override
            public ExecOutcome await(Duration timeout) throws InterruptedException {
                try {
                    return future.get(Math.max(1, timeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    return ExecOutcome.UNKNOWN;
                } catch (java.util.concurrent.ExecutionException e) {
                    return ExecOutcome.FAILED;
                }
            }

            @Override
            public void cancel() {
                future.cancel(true);
            }
        };
    }

    private HttpRequest buildRequest(ExecRequest request) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("runId", request.runId());
        body.put("provider", env(request, "provider", defaultProvider));
        body.put("model", env(request, "model", defaultModel));
        String base = env(request, "baseUrl", null);
        if (base != null) {
            body.put("baseUrl", base);
        }
        String apiKey = env(request, "apiKey", null);
        if (apiKey != null) {
            body.put("apiKey", apiKey);
        }
        body.put("systemPrompt", request.systemPrompt() == null ? "" : request.systemPrompt());
        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.prompt() == null ? "" : request.prompt());
        body.putArray("tools");
        String callback = env(request, "toolCallbackUrl", null);
        if (callback != null) {
            body.put("toolCallbackUrl", callback);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/v1/runs"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (token != null && !token.isBlank()) {
            builder.header("X-Sidecar-Token", token);
        }
        return builder.build();
    }

    /** 逐行解析 SSE：{@code event:} 记住类型，{@code data:} 处理负载，空行复位。 */
    ExecOutcome consume(Stream<String> lines, ExecSink sink) {
        String[] current = {null};
        ExecOutcome[] outcome = {ExecOutcome.OK};
        lines.forEach(line -> {
            if (line.startsWith("event:")) {
                current[0] = line.substring("event:".length()).trim();
                return;
            }
            if (!line.startsWith("data:")) {
                return;
            }
            String data = line.substring("data:".length()).trim();
            if (data.isEmpty()) {
                return;
            }
            try {
                JsonNode node = objectMapper.readTree(data);
                switch (current[0] == null ? "" : current[0]) {
                    case "message_delta" -> {
                        JsonNode delta = node.path("delta");
                        String text = delta.path("text").asText("");
                        if ("thinking_delta".equals(delta.path("type").asText())) {
                            sink.onThinking(text);
                        } else if (!text.isEmpty()) {
                            sink.onText(text);
                        }
                    }
                    case "tool_execution_start" -> sink.onToolCall(
                            node.path("toolCallId").asText(""),
                            node.path("toolName").asText(""),
                            node.path("args").toString());
                    case "tool_execution_end" -> sink.onToolResult(
                            node.path("toolCallId").asText(""),
                            node.path("result").toString(),
                            node.path("isError").asBoolean(false));
                    case "run_end" -> outcome[0] = ExecOutcome.OK;
                    case "error" -> {
                        outcome[0] = ExecOutcome.FAILED;
                        sink.onFinish(ExecOutcome.FAILED, node.path("message").asText("sidecar error"));
                    }
                    default -> {
                        // run_start / message_start 等无需翻译
                    }
                }
            } catch (Exception e) {
                log.debug("无法解析的 sidecar 事件：{}", e.getMessage());
            }
        });
        sink.onFinish(outcome[0], "sidecar stream closed");
        return outcome[0];
    }

    private static String env(ExecRequest request, String key, String fallback) {
        if (request.env() == null) {
            return fallback;
        }
        String value = request.env().get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
