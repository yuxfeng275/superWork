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
