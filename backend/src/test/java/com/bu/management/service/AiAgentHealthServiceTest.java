package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bu.management.config.AiAgentProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import javax.sql.DataSource;
import java.sql.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 健康检查的三个状态：DB+侧车都通 UP、侧车挂 DEGRADED、DB 挂 DOWN；
 * 侧车探测有超时且异常不抛出。
 */
class AiAgentHealthServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("数据库与侧车都可用 → UP")
    void upWhenBothHealthy() throws Exception {
        DataSource dataSource = dataSource(true);
        AiAgentProperties properties = properties(sidecar(200));

        AiAgentHealthService.Health health = new AiAgentHealthService(dataSource, properties).check();

        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.db()).isTrue();
        assertThat(health.sidecar()).isTrue();
    }

    @Test
    @DisplayName("数据库可用、侧车 500 → DEGRADED")
    void degradedWhenSidecarFails() throws Exception {
        DataSource dataSource = dataSource(true);
        AiAgentProperties properties = properties(sidecar(500));

        AiAgentHealthService.Health health = new AiAgentHealthService(dataSource, properties).check();

        assertThat(health.status()).isEqualTo("DEGRADED");
        assertThat(health.db()).isTrue();
        assertThat(health.sidecar()).isFalse();
        assertThat(health.detail()).contains("侧车");
    }

    @Test
    @DisplayName("数据库不可用 → DOWN（即使侧车正常）")
    void downWhenDbFails() throws Exception {
        DataSource dataSource = dataSource(false);
        AiAgentProperties properties = properties(sidecar(200));

        AiAgentHealthService.Health health = new AiAgentHealthService(dataSource, properties).check();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.db()).isFalse();
        assertThat(health.detail()).contains("数据库");
    }

    private static DataSource dataSource(boolean valid) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(valid);
        return dataSource;
    }

    private String sidecar(int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthz", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes();
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static AiAgentProperties properties(String url) {
        AiAgentProperties properties = new AiAgentProperties();
        ReflectionTestUtils.setField(properties, "url", url);
        return properties;
    }
}
