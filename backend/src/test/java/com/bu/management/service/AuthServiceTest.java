package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.JwtProperties;
import com.bu.management.dto.LoginRequest;
import com.bu.management.dto.RegisterRequest;
import com.bu.management.entity.User;
import com.bu.management.mapper.UserMapper;
import com.bu.management.util.JwtUtil;
import com.bu.management.vo.AuthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 测试")
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private JwtProperties jwtProperties;

    private AuthService authService;

    private RegisterRequest validRegisterRequest;
    private LoginRequest validLoginRequest;
    private User existingUser;

    @BeforeEach
    void setUp() {
        // 手动创建 AuthService 并注入 mock 依赖
        authService = new AuthService(userMapper, passwordEncoder, jwtUtil, jwtProperties);

        validRegisterRequest = new RegisterRequest();
        validRegisterRequest.setUsername("testuser");
        validRegisterRequest.setPassword("password123");
        validRegisterRequest.setRealName("测试用户");
        validRegisterRequest.setRole("ADMIN");
        validRegisterRequest.setEmail("test@example.com");
        validRegisterRequest.setPhone("13800138000");

        validLoginRequest = new LoginRequest();
        validLoginRequest.setUsername("testuser");
        validLoginRequest.setPassword("password123");

        existingUser = new User();
        existingUser.setId(1L);
        existingUser.setUsername("testuser");
        existingUser.setPassword("encodedPassword");
        existingUser.setRealName("测试用户");
        existingUser.setRole("ADMIN");
        existingUser.setEmail("test@example.com");
        existingUser.setPhone("13800138000");
        existingUser.setStatus(1);
        existingUser.setCreatedAt(LocalDateTime.now());
        existingUser.setUpdatedAt(LocalDateTime.now());
    }

    @Nested
    @DisplayName("register 方法测试")
    class RegisterTests {

        @Test
        @DisplayName("注册成功")
        void register_validRequest_success() {
            // given
            when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(passwordEncoder.encode("password123")).thenReturn("encodedPassword");
            when(jwtProperties.getExpiration()).thenReturn(86400000L);
            when(jwtUtil.generateToken(any(), any(), any())).thenReturn("access-token");
            when(jwtUtil.generateRefreshToken(any(), any())).thenReturn("refresh-token");

            // when
            AuthResponse result = authService.register(validRegisterRequest);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getAccessToken()).isEqualTo("access-token");
            assertThat(result.getRefreshToken()).isEqualTo("refresh-token");
            assertThat(result.getTokenType()).isEqualTo("Bearer");
            assertThat(result.getExpiresIn()).isEqualTo(86400);
            assertThat(result.getUserInfo()).isNotNull();
            assertThat(result.getUserInfo().getUsername()).isEqualTo("testuser");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userMapper).insert(userCaptor.capture());
            assertThat(userCaptor.getValue().getPassword()).isEqualTo("encodedPassword");
            assertThat(userCaptor.getValue().getStatus()).isEqualTo(1);
        }

        @Test
        @DisplayName("用户名已存在时抛出异常")
        void register_duplicateUsername_throwsException() {
            // given
            when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

            // when & then
            assertThatThrownBy(() -> authService.register(validRegisterRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("用户名已存在");
        }

        @Test
        @DisplayName("邮箱已被使用时抛出异常")
        void register_duplicateEmail_throwsException() {
            // given
            when(userMapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L)  // 用户名检查通过
                    .thenReturn(1L); // 邮箱检查失败

            // when & then
            assertThatThrownBy(() -> authService.register(validRegisterRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("邮箱已被使用");
        }
    }

    @Nested
    @DisplayName("login 方法测试")
    class LoginTests {

        @Test
        @DisplayName("登录成功")
        void login_validCredentials_success() {
            // given
            when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingUser);
            when(passwordEncoder.matches("password123", "encodedPassword")).thenReturn(true);
            when(jwtProperties.getExpiration()).thenReturn(86400000L);
            when(jwtUtil.generateToken(any(), any(), any())).thenReturn("access-token");
            when(jwtUtil.generateRefreshToken(any(), any())).thenReturn("refresh-token");

            // when
            AuthResponse result = authService.login(validLoginRequest);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getAccessToken()).isEqualTo("access-token");
            assertThat(result.getUserInfo()).isNotNull();
            assertThat(result.getUserInfo().getUsername()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("用户不存在时抛出异常")
        void login_userNotFound_throwsException() {
            // given
            when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> authService.login(validLoginRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("用户名或密码错误");
        }

        @Test
        @DisplayName("密码错误时抛出异常")
        void login_wrongPassword_throwsException() {
            // given
            LoginRequest wrongPasswordRequest = new LoginRequest();
            wrongPasswordRequest.setUsername("testuser");
            wrongPasswordRequest.setPassword("wrongPassword");

            when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingUser);
            when(passwordEncoder.matches("wrongPassword", "encodedPassword")).thenReturn(false);

            // when & then
            assertThatThrownBy(() -> authService.login(wrongPasswordRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("用户名或密码错误");
        }

        @Test
        @DisplayName("用户已禁用时抛出异常")
        void login_disabledUser_throwsException() {
            // given
            existingUser.setStatus(0);
            when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingUser);
            when(passwordEncoder.matches("password123", "encodedPassword")).thenReturn(true);

            // when & then
            assertThatThrownBy(() -> authService.login(validLoginRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("用户已被禁用");
        }
    }
}
