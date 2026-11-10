package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiAgentUsage;
import com.bu.management.integration.DigestLlmClient;
import com.bu.management.mapper.AiAgentUsageMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** AiCallRecorder：成功落 SUCCESS、异常落 FAILED 且原样抛出、落库失败不影响主流程。 */
@ExtendWith(MockitoExtension.class)
class AiCallRecorderTest {

    @Mock private DigestLlmClient digestLlmClient;
    @Mock private AiAgentUsageMapper usageMapper;

    private AiCallRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new AiCallRecorder(digestLlmClient, usageMapper);
    }

    @Test
    void recordsSuccessRowWithLatency() {
        JsonNode json = new ObjectMapper().createObjectNode().put("coreWork", "周报正文");
        when(digestLlmClient.chat(anyString(), anyString(), any()))
                .thenReturn(new DigestLlmClient.ChatResult(json, "glm", "glm-5.3"));

        DigestLlmClient.ChatResult result = recorder.call(
                new AiCallRecorder.CallSpec("weekly-summary", 3, 42L, "glm"),
                "系统提示词", "用户输入", node -> { });

        assertThat(result.model()).isEqualTo("glm-5.3");
        AiAgentUsage row = capturedRow();
        assertThat(row.getStatus()).isEqualTo("SUCCESS");
        assertThat(row.getLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(row.getProvider()).isEqualTo("glm");
        assertThat(row.getModel()).isEqualTo("glm-5.3");
        assertThat(row.getPromptCode()).isEqualTo("weekly-summary");
        assertThat(row.getPromptVersion()).isEqualTo(3);
        assertThat(row.getTaskId()).isEqualTo(42L);
        assertThat(row.getRunId()).isNotBlank();
        assertThat(row.getCreatedAt()).isNotNull();
        // 用量字段不落值（DigestLlmClient 不回传 usage），由库默认值兜底
        assertThat(row.getTotalTokens()).isNull();
        assertThat(row.getCostUsd()).isNull();
    }

    @Test
    void recordsFailedRowAndRethrowsOriginalException() {
        when(digestLlmClient.chat(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("所有摘要模型均失败"));

        assertThatThrownBy(() -> recorder.call(
                new AiCallRecorder.CallSpec("weekly-summary", 1, null, "deepseek"),
                "系统提示词", "用户输入", node -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("所有摘要模型均失败");

        AiAgentUsage row = capturedRow();
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(row.getProvider()).isEqualTo("deepseek"); // 失败时用 providerHint 兜底
        assertThat(row.getPromptCode()).isEqualTo("weekly-summary");
    }

    @Test
    void recordFailureDoesNotBreakMainFlow() {
        JsonNode json = new ObjectMapper().createObjectNode().put("ok", true);
        when(digestLlmClient.chat(anyString(), anyString(), any()))
                .thenReturn(new DigestLlmClient.ChatResult(json, "glm", "glm-5.3"));
        when(usageMapper.insert(any(AiAgentUsage.class)))
                .thenThrow(new IllegalStateException("表不存在"));

        DigestLlmClient.ChatResult result = recorder.call(
                new AiCallRecorder.CallSpec("weekly-summary", 1, null, "glm"),
                "系统提示词", "用户输入", node -> { });

        assertThat(result).isNotNull();
        assertThat(result.model()).isEqualTo("glm-5.3");
        verify(usageMapper).insert(any(AiAgentUsage.class));
    }

    private AiAgentUsage capturedRow() {
        ArgumentCaptor<AiAgentUsage> captor = ArgumentCaptor.forClass(AiAgentUsage.class);
        verify(usageMapper).insert(captor.capture());
        return captor.getValue();
    }
}
