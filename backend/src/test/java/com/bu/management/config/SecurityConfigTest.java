package com.bu.management.config;

import com.bu.management.controller.AuthController;
import com.bu.management.security.JwtAuthenticationFilter;
import com.bu.management.security.PermissionInterceptor;
import com.bu.management.service.AuthService;
import com.bu.management.util.JwtUtil;
import com.bu.management.vo.AuthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@ContextConfiguration(classes = {
        SecurityConfigTest.TestApplication.class,
        AuthController.class,
        SecurityConfig.class,
        JwtAuthenticationFilter.class
})
class SecurityConfigTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    private static final String REGISTER_BODY = """
            {
              "username": "new_user",
              "password": "password123",
              "realName": "新用户",
              "role": "DIRECTOR"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private PermissionInterceptor permissionInterceptor;

    @Test
    void anonymousRegistrationIsRejected() throws Exception {
        when(authService.register(any())).thenReturn(AuthResponse.builder().build());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void executionRoleCannotRegisterPrivilegedAccounts() throws Exception {
        when(authService.register(any())).thenReturn(AuthResponse.builder().build());

        mockMvc.perform(post("/api/auth/register")
                        .with(user("engineer").roles("FULL_STACK_ENGINEER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isForbidden());
    }
}
