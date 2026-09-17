package com.bu.management.controller;

import com.bu.management.exception.GlobalExceptionHandler;
import com.bu.management.security.PermissionInterceptor;
import com.bu.management.service.UserService;
import com.bu.management.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(GlobalExceptionHandler.class)
@ContextConfiguration(classes = {
        UserControllerTest.TestApplication.class,
        UserController.class
})
@DisplayName("UserController 测试")
class UserControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private PermissionInterceptor permissionInterceptor;

    @MockBean
    private JwtUtil jwtUtil;

    @Test
    @WithMockUser
    @DisplayName("DELETE /api/users/{id} 物理删除成功")
    void delete_physicalDelete_success() throws Exception {
        when(permissionInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(userService.delete(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/users/1").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("删除成功"));
    }

    @Test
    @WithMockUser
    @DisplayName("DELETE /api/users/{id} 存在关联数据时停用用户")
    void delete_referencedUser_disablesInsteadOf500() throws Exception {
        when(permissionInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(userService.delete(1L)).thenReturn(false);

        mockMvc.perform(delete("/api/users/1").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("用户存在关联业务数据，已停用"));
    }
}
