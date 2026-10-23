package com.bu.management.integration;

import com.bu.management.service.AiModelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 摘要类 LLM 调用的统一入口（周报生成 / 会议纪要共用）。
 * 按「模型管理」排序依次尝试勾选「摘要」的模型：首选因额度、鉴权、超时或返回不合法失败时
 * 自动换下一个，全部失败才抛异常并汇总每个候选的失败原因。
 */
@Slf4j
@Component
public class DigestLlmClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final String EMPTY_MODEL_HINT =
            "AI 模型未配置或未启用，请在「模型管理」勾选「摘要使用」的模型并补全接口地址";

    /** 一次成功调用的结果：JSON 内容 + 实际使用的模型（供 generation_provider/model 落库）。 */
    public record ChatResult(JsonNode json, String providerCode, String model) {}

    private final AiModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public DigestLlmClient(AiModelConfigService modelConfigService, ObjectMapper objectMapper) {
        this(modelConfigService, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    DigestLlmClient(AiModelConfigService modelConfigService, ObjectMapper objectMapper,
                    HttpClient httpClient) {
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** 可用摘要模型数量；0 表示「模型管理」里没有可用的摘要模型。 */
    public int candidateCount() {
        return modelConfigService.digestModels().size();
    }

    /** 候选模型清单文案（provider/model），用于错误提示与日志。 */
    public String describeCandidates() {
        List<AiModelConfigService.DigestModel> models = modelConfigService.digestModels();
        if (models.isEmpty()) {
            return EMPTY_MODEL_HINT;
        }
        return String.join("、", models.stream()
                .map(model -> model.providerCode() + "/" + model.model()).toList());
    }

    /**
     * 按候选顺序调用 chat/completions（json_object），返回首个成功结果。
     *
     * @throws IllegalStateException 无候选模型，或所有候选都失败（消息含每个候选的失败原因）
     */
    public ChatResult chat(String systemPrompt, String userInput) {
        List<AiModelConfigService.DigestModel> candidates = modelConfigService.digestModels();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(EMPTY_MODEL_HINT);
        }
        List<String> failures = new ArrayList<>();
        for (AiModelConfigService.DigestModel model : candidates) {
            try {
                return new ChatResult(call(model, systemPrompt, userInput),
                        model.providerCode(), model.model());
            } catch (RuntimeException e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("摘要模型调用失败，尝试下一个候选 model={}/{}: {}",
                        model.providerCode(), model.model(), reason);
                failures.add(model.providerCode() + "/" + model.model() + " " + reason);
            }
        }
        throw new IllegalStateException("所有摘要模型均失败：" + String.join("；", failures));
    }

    private JsonNode call(AiModelConfigService.DigestModel model, String systemPrompt,
                          String userInput) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.model());
        body.put("response_format", Map.of("type", "json_object"));
        body.put("temperature", 0.1);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userInput)));
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(model.baseUrl()) + "/chat/completions"))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + model.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("请求构建失败：" + e.getMessage(), e);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("调用失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用被中断", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + shortBody(response.body()));
        }
        String text;
        try {
            text = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content").asText(null);
        } catch (IOException e) {
            throw new IllegalStateException("响应不是合法 JSON：" + e.getMessage(), e);
        }
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("返回缺少 content");
        }
        try {
            JsonNode json = objectMapper.readTree(text);
            if (json == null || !json.isObject()) {
                throw new IllegalStateException("返回内容不是 JSON 对象");
            }
            return json;
        } catch (IOException e) {
            throw new IllegalStateException("返回内容不是合法 JSON：" + e.getMessage(), e);
        }
    }

    /** 错误体截断后带进异常信息，让「余额不足 / 鉴权失败」一眼可见。 */
    private static String shortBody(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        String trimmed = body.trim();
        return "（" + (trimmed.length() > 160 ? trimmed.substring(0, 160) + "…" : trimmed) + "）";
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
