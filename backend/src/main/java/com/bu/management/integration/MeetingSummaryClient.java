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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会议总结 LLM 客户端：复用「模型管理」中勾选「摘要」用途的模型（地址与凭据来自连接器），
 * 与邮件摘要/周报同一套模型解析（GLM 优先、DeepSeek 兜底由管理端排序决定）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingSummaryClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final AiModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 生成使用的模型标识（baseUrl/model），用于 generation_model 落库。 */
    public String describeModel() {
        AiModelConfigService.DigestModel model = digestModel();
        return trimSlash(model.baseUrl()) + "/" + model.model();
    }

    public JsonNode chatJson(String systemPrompt, String userInput) {
        AiModelConfigService.DigestModel model = digestModel();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.model());
        body.put("response_format", Map.of("type", "json_object"));
        body.put("temperature", 0.1);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userInput)));
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(model.baseUrl()) + "/chat/completions"))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + model.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("会议总结模型调用失败(" + response.statusCode() + ")");
            }
            String content = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content").asText(null);
            if (content == null) {
                throw new IllegalStateException("会议总结模型返回缺少内容");
            }
            return objectMapper.readTree(content);
        } catch (IOException e) {
            throw new IllegalStateException("会议总结模型调用失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("会议总结模型调用被中断", e);
        }
    }

    private AiModelConfigService.DigestModel digestModel() {
        return modelConfigService.digestModel().orElseThrow(() -> new IllegalStateException(
                "AI 模型未配置或未启用，请在「模型管理」中配置可用于摘要的模型"));
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
