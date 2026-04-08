package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.UserRequest;
import com.bu.management.entity.User;
import com.bu.management.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 测试")
class UserServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    private UserRequest validUserRequest;
    private User existingUser;

    @BeforeEach
    void setUp() {
        validUserRequest = new UserRequest();
        validUserRequest.setUsername("testuser");
        validUserRequest.setPassword("password123");
        validUserRequest.setRealName("测试用户");
        validUserRequest.setRole("ADMIN");
        validUserRequest.setEmail("test@example.com");
        validUserRequest.setPhone("13800138000");
        validUserRequest.setStatus(1);

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
    @DisplayName("create 方法测试")
    class CreateTests {

        @Test
        @DisplayName("创建用户成功")
        void create_validRequest_success() {
            // given
            when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(passwordEncoder.encode("password123")).thenReturn("encodedPassword");
            doAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(1L);
                user.setCreatedAt(LocalDateTime.now());
                return null;
            }).when(userMapper).insert(any(User.class));

            // when
            User result = userService.create(validUserRequest);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getUsername()).isEqualTo("testuser");
            assertThat(result.getPassword()).isNull(); // 密码应该被清除
            assertThat(result.getRealName()).isEqualTo("测试用户");

            // 验证密码编码器被调用
            verify(passwordEncoder).encode("password123");
        }

        @Test
        @DisplayName("用户名已存在时抛出异常")
        void create_duplicateUsername_throwsException() {
            // given
            when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

            // when & then
            assertThatThrownBy(() -> userService.create(validUserRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("用户名已存在");
        }

        @Test
        @DisplayName("邮箱已存在时抛出异常")
        void create_duplicateEmail_throwsException() {
            // given
            when(userMapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L)  // 用户名检查通过
                    .thenReturn(1L); // 邮箱检查失败

            // when & then
            assertThatThrownBy(() -> userService.create(validUserRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("邮箱已存在");
        }

        @Test
        @DisplayName("密码为空时抛出异常")
        void create_emptyPassword_throwsException() {
            // given
            validUserRequest.setPassword(null);

            // when & then
            assertThatThrownBy(() -> userService.create(validUserRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("密码不能为空");
        }
    }

    @Nested
    @DisplayName("update 方法测试")
    class UpdateTests {

        @Test
        @DisplayName("更新用户成功")
        void update_validRequest_success() {
            // given
            when(userMapper.selectById(1L)).thenReturn(existingUser);
            when(userMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

            UserRequest updateRequest = new UserRequest();
            updateRequest.setUsername("testuser");
            updateRequest.setRealName("更新后的姓名");
            updateRequest.setRole("ADMIN");
            updateRequest.setEmail("new@example.com");

            // when
            User result = userService.update(1L, updateRequest);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getRealName()).isEqualTo("更新后的姓名");
            assertThat(result.getPassword()).isNull();
            verify(userMapper).updateById(any(User.class));
        }

        @Test
        @DisplayName("更新不存在的用户时抛出异常")
        void update_userNotFound_throwsException() {
            // given
            when(userMapper.selectById(99L)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> userService.update(99L, validUserRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("用户不存在");
        }

        @Test
        @DisplayName("更新时设置新密码")
        void update_withNewPassword_encodesPassword() {
            // given
            when(userMapper.selectById(1L)).thenReturn(existingUser);

            UserRequest updateRequest = new UserRequest();
            updateRequest.setUsername("testuser");
            updateRequest.setPassword("newPassword");
            updateRequest.setRealName("测试用户");
            updateRequest.setRole("ADMIN");

            // when
            userService.update(1L, updateRequest);

            // then - 验证密码编码器被调用（密码在 updateById 之前被设置，但因为引用问题会被后续清除）
            verify(passwordEncoder).encode("newPassword");
        }
    }

    @Nested
    @DisplayName("updateStatus 方法测试")
    class UpdateStatusTests {

        @Test
        @DisplayName("禁用用户成功")
        void updateStatus_userExists_success() {
            // given
            when(userMapper.selectById(1L)).thenReturn(existingUser);

            // when
            userService.updateStatus(1L, 0);

            // then
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userMapper).updateById(userCaptor.capture());
            assertThat(userCaptor.getValue().getStatus()).isEqualTo(0);
        }

        @Test
        @DisplayName("更新不存在的用户状态时抛出异常")
        void updateStatus_userNotFound_throwsException() {
            // given
            when(userMapper.selectById(99L)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> userService.updateStatus(99L, 0))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("用户不存在");
        }
    }

    @Nested
    @DisplayName("getById 方法测试")
    class GetByIdTests {

        @Test
        @DisplayName("获取用户详情成功")
        void getById_userExists_returnsUser() {
            // given
            when(userMapper.selectById(1L)).thenReturn(existingUser);

            // when
            User result = userService.getById(1L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getUsername()).isEqualTo("testuser");
            assertThat(result.getPassword()).isNull(); // 密码应该被清除
        }

        @Test
        @DisplayName("获取不存在的用户时抛出异常")
        void getById_userNotFound_throwsException() {
            // given
            when(userMapper.selectById(99L)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> userService.getById(99L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("用户不存在");
        }
    }

    @Nested
    @DisplayName("list 方法测试")
    class ListTests {

        @Test
        @DisplayName("分页查询用户列表成功")
        void list_withPagination_success() {
            // given
            Page<User> mockPage = new Page<>(1, 10);
            mockPage.setRecords(java.util.List.of(existingUser));
            mockPage.setTotal(1);

            when(userMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(mockPage);

            // when
            Page<User> result = userService.list(1, 10, null, null, null, null);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getRecords()).hasSize(1);
            assertThat(result.getTotal()).isEqualTo(1);
            assertThat(result.getRecords().get(0).getPassword()).isNull();
        }

        @Test
        @DisplayName("按用户名模糊查询")
        void list_withUsernameFilter_success() {
            // given
            Page<User> mockPage = new Page<>(1, 10);
            mockPage.setRecords(java.util.List.of(existingUser));
            mockPage.setTotal(1);

            when(userMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                    .thenReturn(mockPage);

            // when
            Page<User> result = userService.list(1, 10, "test", null, null, null);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getRecords()).hasSize(1);
        }
    }
}
