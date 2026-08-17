package com.bu.management.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.constant.PositionRoles;
import com.bu.management.dto.UserRequest;
import com.bu.management.entity.SysRole;
import com.bu.management.entity.SysUserRole;
import com.bu.management.mapper.SysRoleMapper;
import com.bu.management.mapper.SysUserRoleMapper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    @Mock
    private SysUserRoleMapper sysUserRoleMapper;

    @Mock
    private SysRoleMapper sysRoleMapper;

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
        validUserRequest.setRole("SOLUTION_MANAGER");
        validUserRequest.setEmail("test@example.com");
        validUserRequest.setPhone("13800138000");
        validUserRequest.setStatus(1);

        existingUser = new User();
        existingUser.setId(1L);
        existingUser.setUsername("testuser");
        existingUser.setPassword("encodedPassword");
        existingUser.setRealName("测试用户");
        existingUser.setRole("SOLUTION_MANAGER");
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
            mockActiveRole(20L);
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
            ArgumentCaptor<SysUserRole> userRoleCaptor = ArgumentCaptor.forClass(SysUserRole.class);
            verify(sysUserRoleMapper).insert(userRoleCaptor.capture());
            assertThat(userRoleCaptor.getValue().getUserId()).isEqualTo(1L);
            assertThat(userRoleCaptor.getValue().getRoleId()).isEqualTo(20L);
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
            updateRequest.setRole("SOLUTION_MANAGER");
            updateRequest.setEmail("new@example.com");
            mockActiveRole(20L);

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
            updateRequest.setRole("SOLUTION_MANAGER");
            mockActiveRole(20L);

            // when
            userService.update(1L, updateRequest);

            // then - 验证密码编码器被调用（密码在 updateById 之前被设置，但因为引用问题会被后续清除）
            verify(passwordEncoder).encode("newPassword");
        }

        @Test
        @DisplayName("超级管理员更新时保持DIRECTOR角色和启用状态")
        void update_superAdmin_keepsDirectorAndEnabled() {
            // given
            existingUser.setUsername("admin");
            existingUser.setRole(PositionRoles.DIRECTOR);
            existingUser.setStatus(1);
            when(userMapper.selectById(1L)).thenReturn(existingUser);

            UserRequest updateRequest = new UserRequest();
            updateRequest.setUsername("admin");
            updateRequest.setRealName("系统管理员");
            updateRequest.setRole(PositionRoles.SOLUTION_MANAGER);
            updateRequest.setStatus(0);
            mockActiveRole(1L);

            // when
            userService.update(1L, updateRequest);

            // then
            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userMapper).updateById(userCaptor.capture());
            assertThat(userCaptor.getValue().getUsername()).isEqualTo("admin");
            assertThat(userCaptor.getValue().getRole()).isEqualTo(PositionRoles.DIRECTOR);
            assertThat(userCaptor.getValue().getStatus()).isEqualTo(1);

            ArgumentCaptor<LambdaQueryWrapper<SysRole>> roleQueryCaptor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(sysRoleMapper).selectOne(roleQueryCaptor.capture());
            LambdaQueryWrapper<SysRole> roleQuery = roleQueryCaptor.getValue();
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    SysRole.class
            );
            roleQuery.getSqlSegment();
            assertThat(roleQuery.getParamNameValuePairs().values())
                    .contains(PositionRoles.DIRECTOR)
                    .doesNotContain(PositionRoles.SOLUTION_MANAGER);
        }

        @Test
        @DisplayName("超级管理员不能改名")
        void update_superAdminRename_throwsException() {
            // given
            existingUser.setUsername("admin");
            when(userMapper.selectById(1L)).thenReturn(existingUser);

            UserRequest updateRequest = new UserRequest();
            updateRequest.setUsername("root");
            updateRequest.setRealName("系统管理员");
            updateRequest.setRole(PositionRoles.DIRECTOR);

            // when & then
            assertThatThrownBy(() -> userService.update(1L, updateRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("超级管理员账号不能改名");
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

        @Test
        @DisplayName("超级管理员不能停用")
        void updateStatus_superAdminDisable_throwsException() {
            // given
            existingUser.setUsername("admin");
            when(userMapper.selectById(1L)).thenReturn(existingUser);

            // when & then
            assertThatThrownBy(() -> userService.updateStatus(1L, 0))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("超级管理员账号不能停用");
            verify(userMapper, never()).updateById(any(User.class));
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
    @DisplayName("delete 方法测试")
    class DeleteTests {

        @Test
        @DisplayName("无业务引用时物理删除用户成功")
        void delete_withoutReferences_success() {
            // given
            when(userMapper.selectById(1L)).thenReturn(existingUser);

            // when
            boolean deleted = userService.delete(1L);

            // then
            assertThat(deleted).isTrue();
            verify(userMapper).deleteById(1L);
            verify(sysUserRoleMapper).delete(any(LambdaQueryWrapper.class));
        }

        @Test
        @DisplayName("存在业务引用时停用用户而不是抛出系统异常")
        void delete_withReferences_disablesUser() {
            // given
            when(userMapper.selectById(1L)).thenReturn(existingUser);
            doThrow(new DataIntegrityViolationException("FK"))
                    .when(userMapper).deleteById(1L);

            // when
            boolean deleted = userService.delete(1L);

            // then
            assertThat(deleted).isFalse();
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(0);
        }

        @Test
        @DisplayName("超级管理员不能删除")
        void delete_superAdmin_throwsException() {
            // given
            existingUser.setUsername("admin");
            when(userMapper.selectById(1L)).thenReturn(existingUser);

            // when & then
            assertThatThrownBy(() -> userService.delete(1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("超级管理员账号不能删除");
            verify(userMapper, never()).deleteById(1L);
            verify(sysUserRoleMapper, never()).delete(any(LambdaQueryWrapper.class));
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

    private void mockActiveRole(Long roleId) {
        SysRole role = new SysRole();
        role.setId(roleId);
        role.setCode("SOLUTION_MANAGER");
        role.setStatus(1);
        when(sysRoleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(role);
    }
}
