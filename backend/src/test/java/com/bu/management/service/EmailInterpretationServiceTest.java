package com.bu.management.service;

import com.bu.management.entity.EmailMessage;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.mapper.EmailMessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailInterpretationServiceTest {
    @Test
    void generatesAndPersistsTraceableInterpretationForOwnedMessage() {
        EmailMessageMapper mapper = mock(EmailMessageMapper.class);
        DeepSeekDigestClient client = mock(DeepSeekDigestClient.class);
        EmailMessage message = new EmailMessage();
        message.setId(42L);
        message.setOwnerUserId(7L);
        message.setSubject("项目交付确认");
        message.setAiInterpretationStatus("NOT_GENERATED");
        when(mapper.selectOne(any())).thenReturn(message);
        when(client.interpret(org.mockito.ArgumentMatchers.eq(message), org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(new EmailInterpretationContent(
                "REPLY",
                "客户要求确认交付时间", "确认项目交付计划",
                "[\"确认交付日期\"]",
                "[{\"content\":\"回复交付日期\",\"priority\":\"高\"}]",
                "[\"延期风险\"]", "已收到，我们将在今日确认交付日期。"));
        when(client.configuredModel()).thenReturn("deepseek-v4-flash");
        EmailInterpretationService service = new EmailInterpretationService(
                mapper, client, new ObjectMapper());

        var result = service.generate(7L, 42L);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.disposition()).isEqualTo("REPLY");
        assertThat(result.summary()).contains("交付时间");
        assertThat(result.actionItems()).hasSize(1);
        assertThat(result.model()).isEqualTo("deepseek-v4-flash");
        assertThat(message.getAiInterpretationJson()).contains("延期风险");
        verify(mapper, org.mockito.Mockito.atLeast(2)).updateById(message);
    }

    @Test
    void persistsFailedStateWithoutLeakingStackTrace() {
        EmailMessageMapper mapper = mock(EmailMessageMapper.class);
        DeepSeekDigestClient client = mock(DeepSeekDigestClient.class);
        EmailMessage message = new EmailMessage();
        message.setId(42L);
        message.setOwnerUserId(7L);
        when(mapper.selectOne(any())).thenReturn(message);
        when(client.interpret(org.mockito.ArgumentMatchers.eq(message), org.mockito.ArgumentMatchers.eq(7L), any())).thenThrow(new IllegalStateException("DeepSeek 未配置或未启用"));
        EmailInterpretationService service = new EmailInterpretationService(
                mapper, client, new ObjectMapper());

        var result = service.generate(7L, 42L);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).isEqualTo("DeepSeek 未配置或未启用");
        assertThat(message.getAiInterpretationStatus()).isEqualTo("FAILED");
    }
}
