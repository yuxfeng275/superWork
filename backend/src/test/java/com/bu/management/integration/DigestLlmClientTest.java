package com.bu.management.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bu.management.service.AiModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 摘要模型兜底链：首选 402/401 时自动换下一个候选，全部失败时汇总每个候选的失败原因。
 */
class DigestLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer first;
    private HttpServer second;

    @AfterEach
    void tearDown() {
        if (first != null) first.stop(0);
        if (second != null) second.stop(0);
    }

    @Test
    void fallsBackToNextModelWhenPrimaryFails() throws Exception {
        AtomicInteger firstHits = new AtomicInteger();
        first = stub(402, "{\"error\":{\"message\":\"Insufficient Balance\"}}", firstHits);
        second = stub(200, chatResponse("{\"summary\":\"ok\"}"), new AtomicInteger());
        AiModelConfigService models = candidates(
                new AiModelConfigService.DigestModel("deepseek", baseUrl(first), "deepseek-flash", "sk-a"),
                new AiModelConfigService.DigestModel("bai", baseUrl(second), "deepseek-v4.1-flash", "sk-b"));

        DigestLlmClient.ChatResult result = new DigestLlmClient(models, objectMapper,
                java.net.http.HttpClient.newHttpClient()).chat("system", "user");

        assertThat(firstHits.get()).isEqualTo(1);
        assertThat(result.providerCode()).isEqualTo("bai");
        assertThat(result.model()).isEqualTo("deepseek-v4.1-flash");
        assertThat(result.json().path("summary").asText()).isEqualTo("ok");
    }

    @Test
    void fallsThroughWhenCandidateAnswersValidJsonButMissingFields() throws Exception {
        // 弱模型常见：返回合法 JSON 对象，但缺 coreWork 之类的必填字段
        first = stub(200, chatResponse("{\"summary\":\"only-summary\"}"), new AtomicInteger());
        second = stub(200, chatResponse("{\"summary\":\"complete\",\"coreWork\":\"ok\"}"),
                new AtomicInteger());
        AiModelConfigService models = candidates(
                new AiModelConfigService.DigestModel("bai", baseUrl(first), "deepseek-v4.1-flash", "sk-b"),
                new AiModelConfigService.DigestModel("deepseek", baseUrl(second), "deepseek-flash", "sk-a"));

        DigestLlmClient.ChatResult result = new DigestLlmClient(models, objectMapper,
                java.net.http.HttpClient.newHttpClient())
                .chat("system", "user", json -> {
                    if (!json.hasNonNull("coreWork")) {
                        throw new IllegalStateException("AI 返回缺少字段: coreWork");
                    }
                });

        assertThat(result.providerCode()).isEqualTo("deepseek");
        assertThat(result.json().path("coreWork").asText()).isEqualTo("ok");
    }

    @Test
    void reportsEveryCandidateWhenAllModelsFail() throws Exception {
        first = stub(401, "{\"error\":{\"message\":\"invalid api key\"}}", new AtomicInteger());
        second = stub(500, "{}", new AtomicInteger());
        AiModelConfigService models = candidates(
                new AiModelConfigService.DigestModel("deepseek", baseUrl(first), "deepseek-flash", "sk-a"),
                new AiModelConfigService.DigestModel("bai", baseUrl(second), "deepseek-v4.1-flash", "sk-b"));

        DigestLlmClient client = new DigestLlmClient(models, objectMapper,
                java.net.http.HttpClient.newHttpClient());

        assertThatThrownBy(() -> client.chat("system", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("所有摘要模型均失败")
                .hasMessageContaining("deepseek/deepseek-flash HTTP 401")
                .hasMessageContaining("bai/deepseek-v4.1-flash HTTP 500");
    }

    @Test
    void honorsConnectorProxyForCandidatesThatNeedIt() throws Exception {
        // 代理桩本身就是转发目标：若客户端没走代理，直连 127.0.0.1:1 必失败
        AtomicInteger proxyHits = new AtomicInteger();
        second = stub(200, chatResponse("{\"summary\":\"proxied\"}"), proxyHits);
        AiModelConfigService models = mock(AiModelConfigService.class);
        when(models.digestModels()).thenReturn(List.of(new AiModelConfigService.DigestModel(
                "bai", "http://127.0.0.1:1/v1", "deepseek-v4.1-flash", "sk-b")));
        when(models.proxyFor("bai")).thenReturn(baseUrl(second));

        DigestLlmClient.ChatResult result = new DigestLlmClient(models, objectMapper).chat("s", "u");

        assertThat(proxyHits.get()).isEqualTo(1);
        assertThat(result.json().path("summary").asText()).isEqualTo("proxied");
        assertThat(result.providerCode()).isEqualTo("bai");
    }

    @Test
    void fallsBackToProxiedCandidateWhenDirectOneIsUnreachable() throws Exception {
        // 生产场景：deepseek 直连超时 → 换 bai（必须走代理）
        AtomicInteger proxyHits = new AtomicInteger();
        second = stub(200, chatResponse("{\"summary\":\"from-bai\"}"), proxyHits);
        AiModelConfigService models = mock(AiModelConfigService.class);
        when(models.digestModels()).thenReturn(List.of(
                new AiModelConfigService.DigestModel("deepseek", "http://127.0.0.1:1/v1",
                        "deepseek-flash", "sk-a"),
                new AiModelConfigService.DigestModel("bai", "http://127.0.0.1:1/v1",
                        "deepseek-v4.1-flash", "sk-b")));
        when(models.proxyFor("deepseek")).thenReturn(null);
        when(models.proxyFor("bai")).thenReturn(baseUrl(second));

        DigestLlmClient.ChatResult result = new DigestLlmClient(models, objectMapper).chat("s", "u");

        assertThat(proxyHits.get()).isEqualTo(1);
        assertThat(result.providerCode()).isEqualTo("bai");
        assertThat(result.json().path("summary").asText()).isEqualTo("from-bai");
    }

    @Test
    void refusesToCallWithoutCandidate() {
        DigestLlmClient client = new DigestLlmClient(candidates(), objectMapper,
                java.net.http.HttpClient.newHttpClient());

        assertThatThrownBy(() -> client.chat("system", "user"))
                .hasMessageContaining("勾选「摘要使用」");
        assertThat(client.candidateCount()).isZero();
    }

    private AiModelConfigService candidates(AiModelConfigService.DigestModel... models) {
        AiModelConfigService service = mock(AiModelConfigService.class);
        when(service.digestModels()).thenReturn(List.of(models));
        return service;
    }

    private HttpServer stub(int status, String body, AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, status, body, hits));
        server.start();
        return server;
    }

    private void respond(HttpExchange exchange, int status, String body, AtomicInteger hits)
            throws IOException {
        hits.incrementAndGet();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private String chatResponse(String content) throws IOException {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "choices", List.of(java.util.Map.of(
                        "message", java.util.Map.of("content", content)))));
    }
}
