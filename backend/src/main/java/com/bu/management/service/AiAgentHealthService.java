package com.bu.management.service;

import com.bu.management.config.AiAgentProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 助手健康检查：不再只报常量 UP，而是验证数据库与侧车两个真实依赖。
 *
 * <p>口径：
 * <ul>
 *   <li>{@code DOWN}：数据库不可用（会话/工具都无法工作），HTTP 503；</li>
 *   <li>{@code DEGRADED}：数据库可用但侧车不可用（会话可读、对话不可用），HTTP 200；</li>
 *   <li>{@code UP}：两者都可用，HTTP 200。</li>
 * </ul>
 *
 * <p>侧车探测带 2 秒超时，健康检查不能被一个挂起的下游拖住。
 *
 * @author BU Team
 * @since 2026-11-18
 */
@Slf4j
@Service
public class AiAgentHealthService {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final DataSource dataSource;

    private final AiAgentProperties properties;

    private final HttpClient client;

    public AiAgentHealthService(DataSource dataSource, AiAgentProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * 健康结果。
     *
     * @param status  UP / DEGRADED / DOWN
     * @param db      数据库是否可用
     * @param sidecar 侧车是否可用
     * @param detail  人话说明（为空表示正常）
     */
    public record Health(String status, boolean db, boolean sidecar, String detail) {
    }

    /** 探测两个依赖；任何异常都转成状态，不抛出。 */
    public Health check() {
        boolean db = dbOk();
        boolean sidecar = sidecarOk();
        if (!db) {
            return new Health("DOWN", false, sidecar, "数据库不可用");
        }
        if (!sidecar) {
            return new Health("DEGRADED", true, false, "侧车不可用（对话不可用，会话可读）");
        }
        return new Health("UP", true, true, "");
    }

    private boolean dbOk() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            log.warn("AI 健康检查：数据库探测失败：{}", e.getMessage());
            return false;
        }
    }

    private boolean sidecarOk() {
        String base = properties.getUrl() == null ? "" : properties.getUrl().trim();
        if (base.isEmpty()) {
            return false;
        }
        String url = base.replaceAll("/+$", "") + "/healthz";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.warn("AI 健康检查：侧车探测失败 url={}：{}", url, e.getMessage());
            return false;
        }
    }
}
