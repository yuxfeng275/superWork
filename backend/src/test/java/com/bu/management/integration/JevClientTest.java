package com.bu.management.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.Connector;
import com.bu.management.service.ConnectorRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("JevClient 测试")
class JevClientTest {

    @Mock
    private ConnectorRegistryService registryService;

    private ObjectMapper objectMapper;
    private JevClient jevClient;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jevClient = new JevClient(registryService);
    }

    @Test
    @DisplayName("noul ≥ 0.5 判定为真，请求体为 systemone 契约（model=jev-latest + noul 问题）")
    void judgeAccepts() throws Exception {
        Connector connector = readyConnector();
        when(registryService.postJson(eq(connector), eq("/v1/systemone"), any(), eq("tok")))
                .thenReturn(objectMapper.readTree(
                        "{\"model\":\"jev-1.13.0\",\"answers\":{\"judge\":{\"type\":\"noul\",\"noul\":0.9}}}"));

        assertThat(jevClient.judge("转写全文", "是否需要纠偏？")).contains(true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(registryService).postJson(eq(connector), eq("/v1/systemone"), body.capture(), eq("tok"));
        assertThat(body.getValue())
                .containsEntry("state", "转写全文")
                .containsEntry("model", "jev-latest");
        assertThat(body.getValue().get("questions")).isNotNull();
    }

    @Test
    @DisplayName("noul < 0.5 判定为假")
    void judgeRejects() throws Exception {
        Connector connector = readyConnector();
        when(registryService.postJson(eq(connector), eq("/v1/systemone"), any(), eq("tok")))
                .thenReturn(objectMapper.readTree(
                        "{\"answers\":{\"judge\":{\"type\":\"noul\",\"noul\":0.2}}}"));

        assertThat(jevClient.judge("state", "q")).contains(false);
    }

    @Test
    @DisplayName("连接器不存在 → 不可用且判定返回 empty")
    void connectorMissing_returnsEmpty() {
        when(registryService.findByCode("typesafe")).thenReturn(Optional.empty());

        assertThat(jevClient.available()).isFalse();
        assertThat(jevClient.judge("state", "q")).isEmpty();
    }

    @Test
    @DisplayName("凭据未配置 → empty；调用异常 → empty（降级为无门控）")
    void missingCredentialOrFailure_returnsEmpty() {
        Connector connector = new Connector();
        connector.setCode("typesafe");
        connector.setBaseUrl("https://api.typesafe.ai");
        when(registryService.findByCode("typesafe")).thenReturn(Optional.of(connector));
        when(registryService.status(connector)).thenReturn("READY");
        when(registryService.credential(connector, "token")).thenReturn(null);
        assertThat(jevClient.judge("state", "q")).isEmpty();

        when(registryService.credential(connector, "token")).thenReturn("tok");
        when(registryService.postJson(eq(connector), eq("/v1/systemone"), any(), eq("tok")))
                .thenThrow(new IllegalStateException("TypeSafe Jev暂时不可用"));
        assertThat(jevClient.judge("state", "q")).isEmpty();
    }

    private Connector readyConnector() {
        Connector connector = new Connector();
        connector.setCode("typesafe");
        connector.setName("TypeSafe Jev");
        connector.setBaseUrl("https://api.typesafe.ai");
        connector.setEnabled(1);
        when(registryService.findByCode("typesafe")).thenReturn(Optional.of(connector));
        when(registryService.status(connector)).thenReturn("READY");
        when(registryService.credential(connector, "token")).thenReturn("tok");
        return connector;
    }
}
