package com.bu.management.controller;

import com.bu.management.dto.WorkflowConfigRequest;
import com.bu.management.entity.WorkflowConfig;
import com.bu.management.exception.GlobalExceptionHandler;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.security.PermissionInterceptor;
import com.bu.management.service.WorkflowConfigService;
import com.bu.management.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowConfigController.class)
@Import(GlobalExceptionHandler.class)
@ContextConfiguration(classes = {
        WorkflowConfigControllerTest.TestApplication.class,
        WorkflowConfigController.class
})
@DisplayName("WorkflowConfigController 测试")
class WorkflowConfigControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkflowConfigService workflowConfigService;

    @MockBean
    private PermissionInterceptor permissionInterceptor;

    @MockBean
    private JwtUtil jwtUtil;

    private ObjectMapper objectMapper;
    private WorkflowConfigRequest request;
    private WorkflowConfig config;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        when(permissionInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        request = new WorkflowConfigRequest();
        request.setRequirementType("项目需求");
        request.setFromStatus("待评估");
        request.setToStatus("评估中");
        request.setAllowedRoles(List.of("项目经理", "技术经理"));
        request.setConditionType("");
        request.setIsActive(1);
        request.setSortOrder(1);

        config = new WorkflowConfig();
        config.setId(1L);
        config.setRequirementType("项目需求");
        config.setFromStatus("待评估");
        config.setToStatus("评估中");
        config.setAllowedRoles("[\"项目经理\",\"技术经理\"]");
        config.setConditionType(null);
        config.setIsActive(1);
        config.setSortOrder(1);
    }

    @Test
    @DisplayName("获取工作流配置列表成功")
    @WithMockUser
    void getAllConfigs_success() throws Exception {
        when(workflowConfigService.getAllConfigs()).thenReturn(List.of(config));

        mockMvc.perform(get("/api/workflow-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].requirementType").value("项目需求"));
    }

    @Nested
    @DisplayName("写接口测试")
    class WriteTests {

        @Test
        @DisplayName("创建工作流配置成功")
        @WithMockUser
        void create_success() throws Exception {
            when(workflowConfigService.create(any(WorkflowConfigRequest.class))).thenReturn(config);

            mockMvc.perform(post("/api/workflow-configs")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("创建成功"))
                    .andExpect(jsonPath("$.data.id").value(1));
        }

        @Test
        @DisplayName("更新不存在的工作流配置时返回404")
        @WithMockUser
        void update_missingConfig_returns404() throws Exception {
            when(workflowConfigService.update(eq(99L), any(WorkflowConfigRequest.class)))
                    .thenThrow(new ResourceNotFoundException("工作流配置不存在"));

            mockMvc.perform(put("/api/workflow-configs/99")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("工作流配置不存在"));
        }

        @Test
        @DisplayName("删除工作流配置成功")
        @WithMockUser
        void delete_success() throws Exception {
            doNothing().when(workflowConfigService).delete(1L);

            mockMvc.perform(delete("/api/workflow-configs/1")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("删除成功"));
        }
    }
}
