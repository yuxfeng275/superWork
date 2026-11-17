package com.bu.management.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bu.management.agent.AgentOrchestrator;
import com.bu.management.agent.AgentRunResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 灰度入口的三个边界：名单外 403、空目标 400、名单内才真正调用编排器。
 * 入口必须在触达模型之前就把人挡住。
 */
@ExtendWith(MockitoExtension.class)
class AgentRunControllerTest {

    @Mock
    private AgentOrchestrator orchestrator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentRunController(orchestrator)).build();
    }

    @Test
    @DisplayName("名单外：403，且不调用编排器")
    void nonGrayUserIsForbidden() throws Exception {
        when(orchestrator.allow(7L)).thenReturn(false);

        mockMvc.perform(post("/api/ai-agent/orchestrate")
                        .requestAttr("userId", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"把本周任务整理一下\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verify(orchestrator, never()).run(any(), any());
    }

    @Test
    @DisplayName("名单内但目标为空：400，且不调用编排器")
    void blankGoalIsRejected() throws Exception {
        when(orchestrator.allow(7L)).thenReturn(true);

        mockMvc.perform(post("/api/ai-agent/orchestrate")
                        .requestAttr("userId", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("名单内：返回编排结果")
    void grayUserRuns() throws Exception {
        when(orchestrator.allow(7L)).thenReturn(true);
        when(orchestrator.run(any(), any())).thenReturn(
                new AgentRunResult(true, "执行 1 步，成功 1 步", List.of(), 12L));

        mockMvc.perform(post("/api/ai-agent/orchestrate")
                        .requestAttr("userId", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"把本周任务整理一下\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));
    }
}
