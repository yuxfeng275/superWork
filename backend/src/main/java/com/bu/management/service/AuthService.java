package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.JwtProperties;
import com.bu.management.dto.LoginRequest;
import com.bu.management.dto.RegisterRequest;
import com.bu.management.entity.User;
import com.bu.management.mapper.UserMapper;
import com.bu.management.util.JwtUtil;
import com.bu.management.vo.AuthResponse;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;

    /**
     * 用户注册
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthResponse register(RegisterRequest request) {
        // 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("用户名已存在");
        }

        // 检查邮箱是否已存在
        if (request.getEmail() != null) {
            wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getEmail, request.getEmail());
            if (userMapper.selectCount(wrapper) > 0) {
                throw new RuntimeException("邮箱已被使用");
            }
        }

        // 创建用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName());
        user.setRole(request.getRole());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(1);

        userMapper.insert(user);

        // 生成 Token
        return generateAuthResponse(user);
    }

    /**
     * 自助修改密码：校验原密码，新密码至少 8 位且不能与原密码相同。
     *
     * <p>改完后旧密码立即失效；当前 JWT 仍可用到过期，前端可选择重新登录。
     *
     * @param userId      当前用户
     * @param oldPassword 原密码
     * @param newPassword 新密码
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!StringUtils.hasText(oldPassword)
                || !passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new RuntimeException("原密码不正确");
        }
        if (!StringUtils.hasText(newPassword) || newPassword.length() < 8) {
            throw new RuntimeException("新密码至少 8 位");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new RuntimeException("新密码不能与原密码相同");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangeRequired(0);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    /**
     * 用户登录
     */
    public AuthResponse login(LoginRequest request) {
        // 查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new RuntimeException("用户名或密码错误");
        }

        // 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }

        // 检查用户状态
        if (user.getStatus() == 0) {
            throw new RuntimeException("用户已被禁用");
        }

        // 生成 Token
        return generateAuthResponse(user);
    }

    /**
     * 生成认证响应
     */
    private AuthResponse generateAuthResponse(User user) {
        String accessToken = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername());

        AuthResponse.UserInfo userInfo = AuthResponse.UserInfo.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .role(user.getRole())
                .email(user.getEmail())
                .phone(user.getPhone())
                .build();

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getExpiration() / 1000)
                .passwordChangeRequired(user.getPasswordChangeRequired() != null
                        && user.getPasswordChangeRequired() == 1)
                .userInfo(userInfo)
                .build();
    }
}
