package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.entity.AiAgentSession;
import com.bu.management.entity.AiAgentUsage;
import com.bu.management.mapper.AiAgentUsageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiAgentUsageServiceTest {

    @Mock private AiAgentUsageMapper usageMapper;

    private AiAgentUsageService service;
    private AiAgentSession session;

    @BeforeEach
    void setUp() {
        service = new AiAgentUsageService(usageMapper, new ObjectMapper());
        session = new AiAgentSession();
        session.setId(5L);
        session.setOwnerUserId(7L);
        session.setProvider("glm");
        session.setModel("glm-5.3");
    }

    @Test
    void sumsAssistantUsagesOfOneRun() {
        when(usageMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service.record(session, "run-1", """
                [{"role":"assistant","provider":"deepseek","model":"deepseek-flash",
                  "usage":{"input":100,"output":40,"cacheRead":10,"cacheWrite":0,
                           "totalTokens":140,"cost":{"total":0.0025}}},
                 {"role":"toolResult","content":[]},
                 {"role":"assistant","provider":"deepseek","model":"deepseek-flash",
                  "usage":{"input":200,"output":60,"cacheRead":0,"cacheWrite":5,
                           "totalTokens":260,"cost":{"total":0.0035}}}]
                """);

        ArgumentCaptor<AiAgentUsage> captor = ArgumentCaptor.forClass(AiAgentUsage.class);
        verify(usageMapper).insert(captor.capture());
        AiAgentUsage row = captor.getValue();
        assertThat(row.getRunId()).isEqualTo("run-1");
        assertThat(row.getSessionId()).isEqualTo(5L);
        assertThat(row.getUserId()).isEqualTo(7L);
        assertThat(row.getProvider()).isEqualTo("deepseek");
        assertThat(row.getModel()).isEqualTo("deepseek-flash");
        assertThat(row.getInputTokens()).isEqualTo(300);
        assertThat(row.getOutputTokens()).isEqualTo(100);
        assertThat(row.getCacheReadTokens()).isEqualTo(10);
        assertThat(row.getCacheWriteTokens()).isEqualTo(5);
        assertThat(row.getTotalTokens()).isEqualTo(400);
        assertThat(row.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.006"));
    }

    @Test
    void ignoresRepeatedRunAndMessagesWithoutUsage() {
        when(usageMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        service.record(session, "run-2", """
                [{"role":"assistant","usage":{"input":10,"totalTokens":10,"cost":{"total":0.1}}}]
                """);
        verify(usageMapper, never()).insert(any(AiAgentUsage.class));

        service.record(session, "run-3", """
                [{"role":"assistant"},{"role":"user","content":"hi"}]
                """);
        verify(usageMapper, never()).insert(any(AiAgentUsage.class));
    }

    @Test
    void summarizesTodayThirtyDaysAndTotal() {
        AiAgentUsage today = usage(1200L, LocalDateTime.now().minusMinutes(5));
        AiAgentUsage lastWeek = usage(800L, LocalDateTime.now().minusDays(7));
        AiAgentUsage old = usage(500L, LocalDateTime.now().minusDays(90));
        when(usageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(today, lastWeek, old));

        AiAgentUsageService.UsageSummary summary = service.summary(7L);

        assertThat(summary.todayRuns()).isEqualTo(1);
        assertThat(summary.todayTokens()).isEqualTo(1200);
        assertThat(summary.monthRuns()).isEqualTo(2);
        assertThat(summary.monthTokens()).isEqualTo(2000);
        assertThat(summary.totalRuns()).isEqualTo(3);
        assertThat(summary.totalTokens()).isEqualTo(2500);
    }

    private AiAgentUsage usage(long tokens, LocalDateTime createdAt) {
        AiAgentUsage row = new AiAgentUsage();
        row.setTotalTokens(tokens);
        row.setCostUsd(new BigDecimal("0.001"));
        row.setCreatedAt(createdAt);
        return row;
    }
}
