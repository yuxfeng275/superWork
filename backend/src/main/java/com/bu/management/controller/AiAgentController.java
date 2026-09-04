package com.bu.management.controller;

import com.bu.management.config.AiAgentProperties;
import com.bu.management.dto.AiAgentSendMessageRequest;
import com.bu.management.dto.CreateAiAgentSessionRequest;
import com.bu.management.service.AiAgentModelConfigService;
import com.bu.management.service.AiAgentSessionService;
import com.bu.management.service.AiAgentToolService;
import com.bu.management.service.ConnectorToolService;
import com.bu.management.vo.AiAgentSessionSummary;
import com.bu.management.vo.AiAgentSessionView;
import com.bu.management.vo.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 助手会话与流式对话（SSE 中继侧车运行流）
 *
 * @author BU Team
 * @since 2026-09-03
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-agent")
@RequiredArgsConstructor
public class AiAgentController {

    private static final String SYSTEM_PROMPT =
            "企业内部管理系统的 AI 助手，可查询当前用户的任务/需求/工时/事项；回答简洁专业；工具返回数据用中文简述";
    private static final String RUN_TIMEOUT_DATA = "{\"code\":\"RUN_TIMEOUT\"}";
    private static final String SIDECAR_UNAVAILABLE_DATA = "{\"code\":\"SIDECAR_UNAVAILABLE\"}";

    private final AiAgentSessionService sessionService;
    private final AiAgentToolService toolService;
    private final AiAgentModelConfigService modelConfigService;
    private final ConnectorToolService connectorToolService;
    private final AiAgentProperties properties;
    private final ObjectMapper objectMapper;

    private final HttpClient sidecarHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();

    @GetMapping("/sessions")
    public Result<List<AiAgentSessionSummary>> sessions(@RequestAttribute("userId") Long userId) {
        return Result.success(sessionService.list(userId));
    }

    /** 可用模型列表（按系统配置 ai-agent 组解析）。 */
    @GetMapping("/models")
    public Result<List<AiAgentModelConfigService.ModelOption>> models() {
        return Result.success(modelConfigService.listAvailableModels());
    }

    /** AI 连接器状态列表（供前端状态面板展示）。 */
    @GetMapping("/connectors")
    public Result<List<ConnectorToolService.ConnectorStatus>> connectors() {
        return Result.success(connectorToolService.statuses());
    }

    @PostMapping("/sessions")
    public Result<AiAgentSessionView> create(@RequestAttribute("userId") Long userId,
            @RequestBody(required = false) CreateAiAgentSessionRequest request) {
        String title = request == null ? null : request.getTitle();
        String provider = request == null ? null : request.getProvider();
        String model = request == null ? null : request.getModel();
        return Result.success(sessionService.create(userId, title, provider, model));
    }

    @GetMapping("/sessions/{id}")
    public Result<AiAgentSessionView> detail(@RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        return Result.success(sessionService.get(userId, id));
    }

    @DeleteMapping("/sessions/{id}")
    public Result<Void> delete(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        sessionService.delete(userId, id);
        return Result.success();
    }

    /**
     * 发送消息：先持久化用户消息，再中继侧车 SSE 流；
     * run_end 时把侧车新增的 AgentMessage 落库。
     */
    @PostMapping("/sessions/{id}/messages")
    public SseEmitter sendMessage(@RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody AiAgentSendMessageRequest request) {
        String content = request.getContent();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("消息内容不能为空");
        }

        // 归属校验 + 用户消息先落库（侧车以现有消息数组续跑）
        sessionService.get(userId, id);
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", content);
        userMessage.put("timestamp", AiAgentSessionService.nowEpochMillis());
        ArrayNode appended = objectMapper.createArrayNode();
        appended.add(userMessage);
        sessionService.appendMessages(id, appended.toString());

        AiAgentSessionView session = sessionService.get(userId, id);
        JsonNode existingMessages = (JsonNode) session.messages();
        AiAgentModelConfigService.ModelConfig model =
                modelConfigService.resolveModelConfig(session.provider());

        String runId = UUID.randomUUID().toString();
        toolService.registerRun(runId, userId);

        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean done = new AtomicBoolean(false);

        // 运行超时定时器：SseEmitter 0L 不超时，由自己计时并发错误事件后收尾
        ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(() -> {
            if (done.compareAndSet(false, true)) {
                toolService.completeRun(runId);
                try {
                    emitter.send(SseEmitter.event().name("error").data(RUN_TIMEOUT_DATA));
                } catch (Exception ignored) {
                    // emitter already dead
                }
                emitter.complete();
            }
        }, properties.getRunTimeoutSeconds(), TimeUnit.SECONDS);

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("runId", runId);
            body.put("provider", session.provider());
            body.put("baseUrl", model.baseUrl());
            body.put("apiKey", model.apiKey());
            body.put("model", model.model());
            body.put("systemPrompt", SYSTEM_PROMPT);
            body.set("messages", existingMessages);
            body.set("tools", objectMapper.valueToTree(toolService.definitions()));
            body.put("toolCallbackUrl", properties.getToolCallbackUrl());

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getUrl() + "/v1/runs"))
                    .timeout(Duration.ofSeconds(properties.getRunTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (!properties.getToken().isBlank()) {
                builder.header("X-Sidecar-Token", properties.getToken());
            }

            sidecarHttpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                    .thenAccept(response -> streamResponse(response.body(), emitter, done,
                            runId, id, timeoutFuture))
                    .exceptionally(throwable -> {
                        log.error("AI 侧车连接失败: {}", throwable.getMessage());
                        timeoutFuture.cancel(false);
                        if (done.compareAndSet(false, true)) {
                            toolService.completeRun(runId);
                            try {
                                emitter.send(SseEmitter.event().name("error")
                                        .data(SIDECAR_UNAVAILABLE_DATA));
                            } catch (Exception ignored) {
                                // emitter already dead
                            }
                            emitter.complete();
                        }
                        return null;
                    });
        } catch (Exception e) {
            timeoutFuture.cancel(false);
            toolService.completeRun(runId);
            log.error("AI 运行请求构建失败", e);
            throw new RuntimeException("AI 运行请求构建失败：" + e.getMessage());
        }
        return emitter;
    }

    /**
     * 逐行解析侧车 SSE（event:/data:，空行分帧），事件名+数据原样转发。
     * run_end 事件额外把 newMessages 落库；error 事件仅转发。
     */
    private void streamResponse(InputStream body, SseEmitter emitter, AtomicBoolean done,
            String runId, Long sessionId, ScheduledFuture<?> timeoutFuture) {
        String eventName = "message";
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).stripLeading());
                } else if (line.isEmpty()) {
                    // 空行 = 分帧，转发该事件
                    if (data.length() > 0) {
                        dispatch(eventName, data.toString(), emitter, done, runId, sessionId);
                    }
                    eventName = "message";
                    data.setLength(0);
                }
                // 注释行（: ping 心跳）忽略
            }
        } catch (IOException e) {
            log.error("AI 运行流中断: runId={}, error={}", runId, e.getMessage());
        } finally {
            timeoutFuture.cancel(false);
            toolService.completeRun(runId);
            if (done.compareAndSet(false, true)) {
                emitter.complete();
            }
        }
    }

    private void dispatch(String eventName, String dataJson, SseEmitter emitter, AtomicBoolean done,
            String runId, Long sessionId) {
        if ("run_end".equals(eventName)) {
            persistNewMessages(sessionId, dataJson);
        }
        // error 等事件仅转发，不落库
        forward(emitter, done, eventName, dataJson);
    }

    /**
     * run_end：从 newMessages 提取侧车新增消息并落库。
     */
    private void persistNewMessages(Long sessionId, String dataJson) {
        try {
            JsonNode node = objectMapper.readTree(dataJson);
            JsonNode newMessages = node.path("newMessages");
            if (newMessages.isArray() && !newMessages.isEmpty()) {
                sessionService.appendMessages(sessionId, newMessages.toString());
            }
        } catch (Exception e) {
            log.error("AI 运行结果落库失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    private void forward(SseEmitter emitter, AtomicBoolean done, String eventName, String dataJson) {
        if (done.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(dataJson));
        } catch (Exception e) {
            log.warn("SSE 转发失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
