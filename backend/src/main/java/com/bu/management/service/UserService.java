package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.UserRequest;
import com.bu.management.entity.User;
import com.bu.management.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户服务
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 创建用户
     */
    @Transactional(rollbackFor = Exception.class)
    public User create(UserRequest request) {
        // 检查用户名是否已存在
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("用户名已存在");
        }

        // 检查邮箱是否已存在
        if (StringUtils.hasText(request.getEmail())) {
            wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getEmail, request.getEmail());
            if (userMapper.selectCount(wrapper) > 0) {
                throw new RuntimeException("邮箱已存在");
            }
        }

        // 密码必填
        if (!StringUtils.hasText(request.getPassword())) {
            throw new RuntimeException("密码不能为空");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRealName(request.getRealName());
        user.setRole(request.getRole());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(request.getStatus() != null ? request.getStatus() : 1);

        userMapper.insert(user);

        // 清除密码字段，不返回给前端
        user.setPassword(null);
        return user;
    }

    /**
     * 更新用户
     */
    @Transactional(rollbackFor = Exception.class)
    public User update(Long id, UserRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 检查用户名是否已被其他用户使用
        if (!user.getUsername().equals(request.getUsername())) {
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getUsername, request.getUsername());
            if (userMapper.selectCount(wrapper) > 0) {
                throw new RuntimeException("用户名已存在");
            }
        }

        // 检查邮箱是否已被其他用户使用
        if (StringUtils.hasText(request.getEmail()) && !request.getEmail().equals(user.getEmail())) {
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(User::getEmail, request.getEmail());
            if (userMapper.selectCount(wrapper) > 0) {
                throw new RuntimeException("邮箱已存在");
            }
        }

        user.setUsername(request.getUsername());
        user.setRealName(request.getRealName());
        user.setRole(request.getRole());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());

        // 如果提供了新密码，则更新密码
        if (StringUtils.hasText(request.getPassword())) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }

        userMapper.updateById(user);

        // 清除密码字段
        user.setPassword(null);
        return user;
    }

    /**
     * 禁用/启用用户
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        user.setStatus(status);
        userMapper.updateById(user);
    }

    /**
     * 获取用户详情
     */
    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 清除密码字段
        user.setPassword(null);
        return user;
    }

    /**
     * 分页查询用户列表
     */
    public Page<User> list(Integer page, Integer size, String username, String realName, String role, Integer status) {
        Page<User> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();

        // 用户名模糊查询
        if (StringUtils.hasText(username)) {
            wrapper.like(User::getUsername, username);
        }

        // 真实姓名模糊查询
        if (StringUtils.hasText(realName)) {
            wrapper.like(User::getRealName, realName);
        }

        // 角色筛选
        if (StringUtils.hasText(role)) {
            wrapper.eq(User::getRole, role);
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(User::getStatus, status);
        }

        // 按创建时间倒序
        wrapper.orderByDesc(User::getCreatedAt);

        Page<User> result = userMapper.selectPage(pageParam, wrapper);

        // 清除所有用户的密码字段
        result.getRecords().forEach(user -> user.setPassword(null));

        return result;
    }
}
