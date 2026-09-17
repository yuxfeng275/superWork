package com.bu.management.security;

import com.bu.management.annotation.RequireUsername;
import com.bu.management.service.SysRoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PermissionInterceptorUsernameTest {

    @Mock
    private SysRoleService sysRoleService;

    private PermissionInterceptor interceptor;
    private HandlerMethod handlerMethod;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new PermissionInterceptor(sysRoleService);
        Method method = UsernameProtectedController.class.getMethod("handle");
        handlerMethod = new HandlerMethod(new UsernameProtectedController(), method);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("user", null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsConfiguredUsernames() throws Exception {
        for (String username : new String[]{"admin", "yufeng"}) {
            MockHttpServletRequest request = request(username);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThat(interceptor.preHandle(request, response, handlerMethod)).isTrue();
        }
    }

    @Test
    void rejectsAuthenticatedUsernameOutsideAllowlist() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("zhangquncheng"), response, handlerMethod);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsRequestWithoutJwtUsernameAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 1L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handlerMethod);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequest request(String username) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 1L);
        request.setAttribute("username", username);
        return request;
    }

    @RequireUsername({"admin", "yufeng"})
    static class UsernameProtectedController {
        public void handle() {
        }
    }
}
