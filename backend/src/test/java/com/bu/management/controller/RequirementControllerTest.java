package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.RequirementRequest;
import com.bu.management.entity.Requirement;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.exception.GlobalExceptionHandler;
import com.bu.management.security.PermissionInterceptor;
import com.bu.management.service.RequirementService;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RequirementController Web 层测试
 */
@WebMvcTest(RequirementController.class)
@Import(GlobalExceptionHandler.class)
@ContextConfiguration(classes = {
        RequirementControllerTest.TestApplication.class,
        RequirementController.class
})
@DisplayName("RequirementController 测试")
class RequirementControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RequirementService requirementService;

    @MockBean
    private PermissionInterceptor permissionInterceptor;

    @MockBean
    private JwtUtil jwtUtil;

    private ObjectMapper objectMapper;
    private RequirementRequest validRequest;
    private Requirement existingRequirement;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        when(permissionInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        validRequest = new RequirementRequest();
        validRequest.setTitle("新需求");
        validRequest.setDescription("需求描述");
        validRequest.setType("产品需求");
        validRequest.setBusinessLineId(1L);
        validRequest.setPriority("高");
        validRequest.setSource("客户反馈");
        validRequest.setExpectedOnlineDate(LocalDate.now().plusDays(30));

        existingRequirement = new Requirement();
        existingRequirement.setId(1L);
        existingRequirement.setReqNo("REQ202604030001");
        existingRequirement.setTitle("已存在的需求");
        existingRequirement.setDescription("描述");
        existingRequirement.setType("产品需求");
        existingRequirement.setBusinessLineId(1L);
        existingRequirement.setStatus("待评估");
        existingRequirement.setPriority("中");
        existingRequirement.setCreatorId(1L);
        existingRequirement.setCreatedAt(LocalDateTime.now());
        existingRequirement.setUpdatedAt(LocalDateTime.now());
    }

    @Nested
    @DisplayName("POST /api/requirements - 创建需求")
    class CreateTests {

        @Test
        @DisplayName("创建需求成功")
        @WithMockUser
        void create_validRequest_success() throws Exception {
            // given
            when(requirementService.create(any(RequirementRequest.class), any(Long.class)))
                    .thenReturn(existingRequirement);

            // when & then
            mockMvc.perform(post("/api/requirements")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.reqNo").value("REQ202604030001"))
                    .andExpect(jsonPath("$.message").value("创建成功"));
        }

        @Test
        @DisplayName("业务线不存在时返回400")
        @WithMockUser
        void create_businessLineNotFound_returns400() throws Exception {
            // given
            when(requirementService.create(any(RequirementRequest.class), any(Long.class)))
                    .thenThrow(new RuntimeException("业务线不存在"));

            // when & then
            mockMvc.perform(post("/api/requirements")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("业务线不存在"));
        }

        @Test
        @DisplayName("请求体为空时返回400")
        @WithMockUser
        void create_emptyBody_returns400() throws Exception {
            // when & then
            mockMvc.perform(post("/api/requirements")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PUT /api/requirements/{id} - 更新需求")
    class UpdateTests {

        @Test
        @DisplayName("更新需求成功")
        @WithMockUser
        void update_validRequest_success() throws Exception {
            // given
            when(requirementService.update(eq(1L), any(RequirementRequest.class)))
                    .thenReturn(existingRequirement);

            // when & then
            mockMvc.perform(put("/api/requirements/1")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("更新成功"));
        }

        @Test
        @DisplayName("需求不存在时返回404")
        @WithMockUser
        void update_requirementNotFound_returns404() throws Exception {
            // given
            when(requirementService.update(eq(99L), any(RequirementRequest.class)))
                    .thenThrow(new ResourceNotFoundException("需求不存在"));

            // when & then
            mockMvc.perform(put("/api/requirements/99")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("需求不存在"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/requirements/{id} - 删除需求")
    class DeleteTests {

        @Test
        @DisplayName("删除需求成功")
        @WithMockUser
        void delete_existingRequirement_success() throws Exception {
            // given
            doNothing().when(requirementService).delete(1L);

            // when & then
            mockMvc.perform(delete("/api/requirements/1")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.message").value("删除成功"));
        }

        @Test
        @DisplayName("删除不存在的需求时返回404")
        @WithMockUser
        void delete_requirementNotFound_returns404() throws Exception {
            // given
            doThrow(new ResourceNotFoundException("需求不存在")).when(requirementService).delete(99L);

            // when & then
            mockMvc.perform(delete("/api/requirements/99")
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("需求不存在"));
        }

        @Test
        @DisplayName("删除非待评估状态需求时返回400")
        @WithMockUser
        void delete_nonPendingRequirement_returns400() throws Exception {
            // given
            doThrow(new RuntimeException("只有待评估状态的需求才能删除")).when(requirementService).delete(1L);

            // when & then
            mockMvc.perform(delete("/api/requirements/1")
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("只有待评估状态的需求才能删除"));
        }
    }

    @Nested
    @DisplayName("GET /api/requirements/{id} - 获取需求详情")
    class GetByIdTests {

        @Test
        @DisplayName("获取需求详情成功")
        @WithMockUser
        void getById_existingRequirement_success() throws Exception {
            // given
            when(requirementService.getById(1L)).thenReturn(existingRequirement);

            // when & then
            mockMvc.perform(get("/api/requirements/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.reqNo").value("REQ202604030001"));
        }

        @Test
        @DisplayName("获取不存在的需求时返回404")
        @WithMockUser
        void getById_requirementNotFound_returns404() throws Exception {
            // given
            when(requirementService.getById(99L)).thenThrow(new ResourceNotFoundException("需求不存在"));

            // when & then
            mockMvc.perform(get("/api/requirements/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("需求不存在"));
        }
    }

    @Nested
    @DisplayName("GET /api/requirements - 分页查询需求列表")
    class ListTests {

        @Test
        @DisplayName("分页查询需求列表成功")
        @WithMockUser
        void list_withPagination_success() throws Exception {
            // given
            Page<Requirement> mockPage = new Page<>(1, 10);
            mockPage.setRecords(List.of(existingRequirement));
            mockPage.setTotal(1);

            when(requirementService.listWithPermission(eq(1L), eq("BU_ADMIN"), eq(1), eq(10),
                            any(), any(), any(), any(), any(), any()))
                    .thenReturn(mockPage);

            // when & then
            mockMvc.perform(get("/api/requirements")
                            .requestAttr("userId", 1L)
                            .requestAttr("role", "BU_ADMIN")
                            .param("page", "1")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.records[0].id").value(1));
        }

        @Test
        @DisplayName("按业务线筛选需求")
        @WithMockUser
        void list_withBusinessLineFilter_success() throws Exception {
            // given
            Page<Requirement> mockPage = new Page<>(1, 10);
            mockPage.setRecords(List.of(existingRequirement));
            mockPage.setTotal(1);

            when(requirementService.listWithPermission(eq(1L), eq("BU_ADMIN"), eq(1), eq(10),
                            eq(1L), any(), any(), any(), any(), any()))
                    .thenReturn(mockPage);

            // when & then
            mockMvc.perform(get("/api/requirements")
                            .requestAttr("userId", 1L)
                            .requestAttr("role", "BU_ADMIN")
                            .param("page", "1")
                            .param("size", "10")
                            .param("businessLineId", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.total").value(1));
        }

        @Test
        @DisplayName("使用默认分页参数")
        @WithMockUser
        void list_withDefaultPagination_success() throws Exception {
            // given
            Page<Requirement> mockPage = new Page<>(1, 10);
            mockPage.setRecords(List.of(existingRequirement));
            mockPage.setTotal(1);

            when(requirementService.listWithPermission(eq(1L), eq("BU_ADMIN"), eq(1), eq(10),
                            any(), any(), any(), any(), any(), any()))
                    .thenReturn(mockPage);

            // when & then
            mockMvc.perform(get("/api/requirements")
                            .requestAttr("userId", 1L)
                            .requestAttr("role", "BU_ADMIN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }
}
