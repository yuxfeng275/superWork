package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.bu.management.config.EmailCredentialCipher;
import static org.mockito.Mockito.mock;

import com.bu.management.entity.Connector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("连接器级前代（extra.proxy）测试")
class ConnectorRegistryServiceProxyTest {

    @Test
    @DisplayName("设置 extra.proxy 时请求经前代（请求行为绝对 URI 形式）")
    void postJson_routesThroughConnectorProxy() throws Exception {
        AtomicReference<String> requestLine = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread acceptor = startFakeProxy(server, requestLine);

            Connector entity = connector(port, true);
            service().postJson(entity, "/v1/systemone", Map.of("state", "x"), null);

            acceptor.join(10_000);
            assertThat(requestLine.get())
                    .isEqualTo("POST http://127.0.0.1:" + port + "/v1/systemone HTTP/1.1");
        }
    }

    @Test
    @DisplayName("未设置 extra.proxy 时直连（请求行为 origin-form）")
    void postJson_withoutProxyGoesDirect() throws Exception {
        AtomicReference<String> requestLine = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread acceptor = startFakeProxy(server, requestLine);

            Connector entity = connector(port, false);
            service().postJson(entity, "/v1/systemone", Map.of("state", "x"), null);

            acceptor.join(10_000);
            assertThat(requestLine.get()).isEqualTo("POST /v1/systemone HTTP/1.1");
        }
    }

    private ConnectorRegistryService service() {
        return new ConnectorRegistryService(
                mock(com.bu.management.mapper.ConnectorMapper.class),
                mock(EmailCredentialCipher.class),
                new ObjectMapper());
    }

    private Connector connector(int port, boolean withProxy) {
        Connector entity = new Connector();
        entity.setName("TypeSafe Jev");
        entity.setBaseUrl("http://127.0.0.1:" + port);
        if (withProxy) {
            entity.setExtraConfig("{\"proxy\":\"http://127.0.0.1:" + port + "\"}");
        }
        return entity;
    }

    /** 假前代/假目标：记录请求行，回 200 {}。 */
    private Thread startFakeProxy(ServerSocket server, AtomicReference<String> requestLine) {
        Thread acceptor = new Thread(() -> {
            try (Socket socket = server.accept()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                requestLine.set(reader.readLine());
                int contentLength = 0;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                int remaining = contentLength;
                char[] body = new char[contentLength];
                while (remaining > 0) {
                    int n = reader.read(body, contentLength - remaining, remaining);
                    if (n < 0) {
                        break;
                    }
                    remaining -= n;
                }
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Content-Length: 2\r\nConnection: close\r\n\r\n{}").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        return acceptor;
    }
}
