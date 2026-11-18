package com.bu.management.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.User;
import com.bu.management.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 强制改密门禁：flag=1 时业务 API 403、认证/健康放行、flag=0 正常。
 */
@ExtendWith(MockitoExtension.class)
class PasswordChangeGateFilterTest {

    @Mock
    private UserMapper userMapper;

    @Test
    @DisplayName("flag=1 的业务请求：403 且不进入业务链")
    void blocksBusinessApiWhenChangeRequired() throws Exception {
        User user = new User();
        user.setId(5L);
        user.setPasswordChangeRequired(1);
        when(userMapper.selectById(5L)).thenReturn(user);
        PasswordChangeGateFilter filter = new PasswordChangeGateFilter(userMapper);
        MockHttpServletRequest request = request("/api/users", 5L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("认证/健康接口放行，不查库")
    void allowsAuthAndHealth() throws Exception {
        PasswordChangeGateFilter filter = new PasswordChangeGateFilter(userMapper);
        MockHttpServletRequest request = request("/api/auth/change-password", 5L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(userMapper, never()).selectById(5L);
    }

    @Test
    @DisplayName("flag=1 时 /api/auth/register 也被拦，不能借注册绕过强制改密")
    void blocksRegisterWhenChangeRequired() throws Exception {
        User user = new User();
        user.setId(5L);
        user.setPasswordChangeRequired(1);
        when(userMapper.selectById(5L)).thenReturn(user);
        PasswordChangeGateFilter filter = new PasswordChangeGateFilter(userMapper);
        MockHttpServletRequest request = request("/api/auth/register", 5L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("flag=0 正常放行")
    void allowsWhenNoChangeRequired() throws Exception {
        User user = new User();
        user.setId(5L);
        user.setPasswordChangeRequired(0);
        when(userMapper.selectById(5L)).thenReturn(user);
        PasswordChangeGateFilter filter = new PasswordChangeGateFilter(userMapper);
        MockHttpServletRequest request = request("/api/tasks", 5L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest request(String uri, Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setAttribute("userId", userId);
        return request;
    }
}
