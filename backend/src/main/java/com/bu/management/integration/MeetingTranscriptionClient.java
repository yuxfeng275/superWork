package com.bu.management.integration;

import com.bu.management.service.MeetingConfigService;
import com.bu.management.service.MeetingConfigService.MeetingRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 会议转写（自托管 worker）客户端：multipart 上传音频，解析匿名分人转写结果。
 * 引擎细节被 HTTP 契约隔离，换引擎只改 asr-worker/。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingTranscriptionClient {

    private final MeetingConfigService configService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** worker 返回段：index 从 1 起，speaker 为录音内匿名标签。 */
    public record Segment(int index, long startMs, long endMs, String speaker, String text) {
    }

    public record TranscriptionResult(long durationMs, String model, List<Segment> segments) {
    }

    public TranscriptionResult transcribe(Path audioFile, String fileName, String hotwords) {
        MeetingRuntimeConfig config = configService.load();
        if (!StringUtils.hasText(config.asrBaseUrl())) {
            throw new IllegalStateException("会议转写未配置");
        }
        String boundary = "----meeting" + UUID.randomUUID().toString().replace("-", "");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(config.asrBaseUrl()) + "/v1/transcriptions"))
                    .timeout(Duration.ofSeconds(Math.max(1, config.asrTimeoutSeconds())))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(multipartBody(boundary, audioFile, fileName, hotwords));
            if (StringUtils.hasText(config.asrToken())) {
                builder.header("X-Meeting-Token", config.asrToken());
            }
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "会议转写失败(" + response.statusCode() + ")：" + errorMessage(response.body()));
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("会议转写调用失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("会议转写被中断", e);
        }
    }

    private TranscriptionResult parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("会议转写响应不是合法 JSON", e);
        }
        JsonNode segmentsNode = root.path("segments");
        if (!segmentsNode.isArray()) {
            throw new IllegalStateException("会议转写响应缺少 segments");
        }
        List<Segment> segments = new ArrayList<>();
        for (JsonNode node : segmentsNode) {
            segments.add(new Segment(
                    node.path("index").asInt(segments.size() + 1),
                    node.path("startMs").asLong(),
                    node.path("endMs").asLong(),
                    node.path("speaker").asText("SPEAKER_00"),
                    node.path("text").asText("")));
        }
        return new TranscriptionResult(
                root.path("durationMs").asLong(), root.path("model").asText(null), segments);
    }

    private String errorMessage(String body) {
        try {
            String message = objectMapper.readTree(body).path("error").path("message").asText(null);
            return message == null ? "" : message;
        } catch (Exception e) {
            return "";
        }
    }

    private HttpRequest.BodyPublisher multipartBody(String boundary, Path audioFile, String fileName,
            String hotwords) throws IOException {
        String safeName = StringUtils.hasText(fileName) ? fileName.replace("\"", "") : "audio";
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        parts.add(HttpRequest.BodyPublishers.ofString(
                "--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\""
                        + safeName + "\"\r\nContent-Type: application/octet-stream\r\n\r\n",
                StandardCharsets.UTF_8));
        parts.add(HttpRequest.BodyPublishers.ofFile(audioFile));
        parts.add(HttpRequest.BodyPublishers.ofString("\r\n", StandardCharsets.UTF_8));
        if (StringUtils.hasText(hotwords)) {
            parts.add(HttpRequest.BodyPublishers.ofString(
                    "--" + boundary + "\r\nContent-Disposition: form-data; name=\"hotword\"\r\n\r\n"
                            + hotwords + "\r\n", StandardCharsets.UTF_8));
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n", StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.concat(parts.toArray(new HttpRequest.BodyPublisher[0]));
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
