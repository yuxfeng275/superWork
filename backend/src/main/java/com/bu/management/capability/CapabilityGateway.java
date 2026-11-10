package com.bu.management.capability;

import com.bu.management.entity.AiCapabilityCall;
import com.bu.management.mapper.AiCapabilityCallMapper;
import com.bu.management.service.JevDecisionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 能力网关：所有能力调用的唯一入口，负责「找得到 / 拦得住 / 跑不完就掐掉 / 每次都留痕」。
 *
 * <p>职责边界刻意收得很窄，只做四件事：
 * <ol>
 *   <li>定位能力（未注册直接失败，不猜）</li>
 *   <li>WRITE 类走 Jev 写操作门禁（拒绝就返回 GATE_DENIED，交给上层复述给用户）</li>
 *   <li>按能力自带超时执行，超时就 cancel（避免一条慢能力拖死整个对话线程）</li>
 *   <li>无论成败落一行 ai_capability_call（写库异常只 warn，审计不能反过来阻断主流程）</li>
 * </ol>
 *
 * <p>异常绝不穿透：Handler 抛什么都在这里变成 {@link CapabilityResult#fail}，
 * 因为调用方是对话链路，一次工具失败不该让整轮对话炸掉。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Slf4j
@Component
public class CapabilityGateway {

    /** 写进结果 / 日志的错误信息上限，防止把一个巨大的堆栈塞进模型上下文。 */
    private static final int ERROR_MESSAGE_LIMIT = 200;

    /** 网关总开关：置 false 时仍允许执行（主要给测试和灰度期留逃生口）。 */
    private static final String PROP_GATEWAY_ENABLED = "ai.platform.gateway-enabled";

    /** 远端能力开关：默认关闭，接通 Python worker 后才打开。 */
    private static final String PROP_REMOTE_ENABLED = "ai.platform.remote-capability-enabled";

    private final CapabilityRegistry registry;
    private final JevDecisionService jevDecisionService;
    private final AiCapabilityCallMapper callMapper;
    private final Environment environment;
    private final String remoteBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final ExecutorService executor;

    public CapabilityGateway(
            CapabilityRegistry registry,
            JevDecisionService jevDecisionService,
            AiCapabilityCallMapper callMapper,
            Environment environment,
            @Value("${ai.platform.remote-capability-base-url:}") String remoteBaseUrl) {
        this.registry = registry;
        this.jevDecisionService = jevDecisionService;
        this.callMapper = callMapper;
        this.environment = environment;
        this.remoteBaseUrl = remoteBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        // 用缓存线程池而不是单线程池：超时的 Handler 线程会被中断但未必立刻退出，
        // 单线程池会让后续所有能力排在那条慢线程后面——一个坏能力拖死全部能力。
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "capability-gateway");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 调用一个能力。
     *
     * @param id    能力编码
     * @param input 入参 JSON，可为 null（按空对象处理）
     * @param ctx   调用上下文，可为 null（按系统调用处理）
     * @return 结果对象，永不为 null，永不抛异常
     */
    public CapabilityResult invoke(String id, JsonNode input, CapabilityContext ctx) {
        long startNanos = System.nanoTime();
        CapabilityContext context = ctx == null ? CapabilityContext.system() : ctx;
        JsonNode payload = input == null ? objectMapper.createObjectNode() : input;

        CapabilityRegistry.Registered registered = registry.lookup(id).orElse(null);
        if (registered == null) {
            // 未知能力也要留痕：模型幻觉出来的工具名是最值得盯的指标。
            log.warn("调用未注册的能力：id={}", id);
            return audit(id, context, CapabilityResult.fail("UNKNOWN_CAPABILITY", id), startNanos);
        }

        boolean gatewayEnabled = Boolean.TRUE.equals(
                environment.getProperty(PROP_GATEWAY_ENABLED, Boolean.class, Boolean.TRUE));
        if (!gatewayEnabled) {
            // 关闭开关不阻断执行，只降噪日志：这个开关是排障用的观察位，不是熔断器。
            log.debug("能力网关开关已关闭，按契约继续执行：id={}", id);
        }

        CapabilityResult result;
        try {
            CapabilityDescriptor descriptor = registered.descriptor();
            if (descriptor.kind() == CapabilityKind.REMOTE) {
                result = invokeRemote(descriptor, payload, context);
            } else {
                if (descriptor.isWrite()) {
                    CapabilityResult denied = gateWrite(descriptor, payload, context);
                    if (denied != null) {
                        return audit(id, context, denied, startNanos);
                    }
                }
                result = invokeLocal(registered, payload, context);
            }
        } catch (RuntimeException e) {
            log.warn("能力网关内部异常：id={}, error={}", id, e.getMessage());
            result = CapabilityResult.fail("GATEWAY_ERROR", truncate(e.getMessage()));
        }
        return audit(id, context, result, startNanos);
    }

    /**
     * Jev 写操作门禁。
     *
     * @return 被拒时返回失败结果；放行返回 null 表示可以继续执行
     */
    private CapabilityResult gateWrite(CapabilityDescriptor descriptor, JsonNode input, CapabilityContext ctx) {
        String userMessage = ctx.runId() == null ? "" : ctx.runId();
        JevDecisionService.WriteGate gate = jevDecisionService.gateWrite(
                descriptor.id(), userMessage, input == null ? "{}" : input.toString());
        if (gate == null || !gate.allow()) {
            String reason = gate == null ? "门禁无响应" : String.valueOf(gate.reason());
            log.info("写能力被 Jev 门禁拦截：id={}, reason={}", descriptor.id(), reason);
            return CapabilityResult.fail("GATE_DENIED", truncate(reason));
        }
        return null;
    }

    /** 本地执行：FutureTask + get(timeout) 做超时控制，超时后 cancel 释放线程。 */
    private CapabilityResult invokeLocal(CapabilityRegistry.Registered registered, JsonNode input,
            CapabilityContext ctx) {
        CapabilityDescriptor descriptor = registered.descriptor();
        CapabilityHandler handler = registered.handler();
        FutureTask<CapabilityResult> task =
                new FutureTask<>(() -> handler.execute(input, ctx));
        executor.execute(task);
        try {
            CapabilityResult result = task.get(descriptor.timeoutMs(), TimeUnit.MILLISECONDS);
            if (result == null) {
                return CapabilityResult.fail("HANDLER_ERROR", "能力返回 null 结果：" + descriptor.id());
            }
            return result;
        } catch (TimeoutException e) {
            // cancel(true) 会中断线程；不保证业务真的停下，但至少不再占着调用方。
            task.cancel(true);
            log.warn("能力执行超时：id={}, timeoutMs={}", descriptor.id(), descriptor.timeoutMs());
            return CapabilityResult.fail("TIMEOUT",
                    "能力执行超时（" + descriptor.timeoutMs() + "ms）：" + descriptor.id());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("能力执行抛异常：id={}, error={}", descriptor.id(), cause.getMessage());
            return CapabilityResult.fail("HANDLER_ERROR", truncate(cause.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return CapabilityResult.fail("INTERRUPTED", "能力调用被中断：" + descriptor.id());
        }
    }

    /**
     * 远端执行：把调用转发给 Python worker。这是未来扩展点，
     * 实现刻意保持最小可用——一个 POST、一次超时、非 2xx 一律转失败。
     */
    private CapabilityResult invokeRemote(CapabilityDescriptor descriptor, JsonNode input,
            CapabilityContext ctx) {
        boolean remoteEnabled = Boolean.TRUE.equals(
                environment.getProperty(PROP_REMOTE_ENABLED, Boolean.class, Boolean.FALSE));
        if (!remoteEnabled) {
            return CapabilityResult.fail("REMOTE_DISABLED",
                    "远端能力开关未开启（" + PROP_REMOTE_ENABLED + "）：" + descriptor.id());
        }
        if (!StringUtils.hasText(remoteBaseUrl)) {
            return CapabilityResult.fail("REMOTE_DISABLED",
                    "未配置远端能力地址（ai.platform.remote-capability-base-url）：" + descriptor.id());
        }

        String base = remoteBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri = URI.create(base + "/capabilities/"
                + URLEncoder.encode(descriptor.id(), StandardCharsets.UTF_8) + "/invoke");

        ObjectNode body = objectMapper.createObjectNode();
        body.set("input", input == null ? objectMapper.createObjectNode() : input);
        ObjectNode contextNode = body.putObject("context");
        contextNode.put("userId", ctx.userId());
        contextNode.put("runId", ctx.runId());
        contextNode.put("taskId", ctx.taskId());
        contextNode.put("unattended", ctx.unattended());
        contextNode.put("traceId", ctx.traceId());
        contextNode.put("idempotencyKey", ctx.idempotencyKey());

        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(descriptor.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return CapabilityResult.fail("REMOTE_ERROR",
                        truncate("HTTP " + response.statusCode() + " " + response.body()));
            }
            return parseRemoteBody(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CapabilityResult.fail("INTERRUPTED", "远端能力调用被中断：" + descriptor.id());
        } catch (Exception e) {
            log.warn("远端能力调用失败：id={}, error={}", descriptor.id(), e.getMessage());
            return CapabilityResult.fail("REMOTE_ERROR", truncate(e.getMessage()));
        }
    }

    /** 远端返回体按 {@link CapabilityResult} 的字段语义解析，解析不了就算失败。 */
    private CapabilityResult parseRemoteBody(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                return CapabilityResult.fail("REMOTE_ERROR", "远端返回不是 JSON 对象");
            }
            boolean ok = node.path("ok").asBoolean(false);
            if (ok) {
                JsonNode data = node.get("dataJson");
                if (data == null || data.isNull()) {
                    data = node.get("data");
                }
                return CapabilityResult.ok(data == null || data.isNull() ? "{}" : data.toString());
            }
            String code = node.path("errorCode").asText("REMOTE_ERROR");
            String message = node.path("errorMessage").asText("远端能力执行失败");
            return CapabilityResult.fail(code, truncate(message));
        } catch (Exception e) {
            return CapabilityResult.fail("REMOTE_ERROR", truncate("远端返回无法解析：" + e.getMessage()));
        }
    }

    /** 回填耗时 + 落审计行。审计失败只 warn：统计缺一行，好过业务失败一次。 */
    private CapabilityResult audit(String capabilityId, CapabilityContext ctx,
            CapabilityResult result, long startNanos) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        CapabilityResult stamped = result.withLatency(latencyMs);
        try {
            AiCapabilityCall row = new AiCapabilityCall();
            row.setCapabilityId(capabilityId);
            row.setUserId(ctx.userId());
            row.setRunId(ctx.runId());
            row.setOk(stamped.ok());
            row.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            row.setErrorCode(stamped.errorCode());
            row.setCreatedAt(LocalDateTime.now());
            callMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("能力调用审计写库失败（不影响调用结果）：capability={}, error={}",
                    capabilityId, e.getMessage());
        }
        return stamped;
    }

    /** 错误消息截断：模型上下文和日志都不适合塞长文本。 */
    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= ERROR_MESSAGE_LIMIT
                ? message
                : message.substring(0, ERROR_MESSAGE_LIMIT);
    }
}
