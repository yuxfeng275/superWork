package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.constant.PositionRoles;
import com.bu.management.dto.UserRequest;
import com.bu.management.entity.SysRole;
import com.bu.management.entity.User;
import com.bu.management.entity.SysUserRole;
import com.bu.management.mapper.SysRoleMapper;
import com.bu.management.mapper.SysUserRoleMapper;
import com.bu.management.mapper.UserMapper;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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

    private static final String SUPER_ADMIN_USERNAME = "admin";

    /**
     * V14 初始化时给所有在岗账号写入的 BCrypt 哈希；仍使用它的账号等于还在用初始密码。
     * 只用于「识别 + 批量重置」的安全审计，不参与登录校验。
     */
    static final String SEED_PASSWORD_HASH =
            "$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;

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

        if (isSuperAdminUsername(request.getUsername())) {
            request.setUsername(SUPER_ADMIN_USERNAME);
            request.setRole(PositionRoles.DIRECTOR);
            request.setStatus(1);
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
        syncUserRole(user.getId(), request.getRole());

        // 清除密码字段，不返回给前端
        user.setPassword(null);
        return user;
    }

    /** 重置密码可用字符：去掉 0/O/1/l/I 等易混字符，方便口头转交。 */
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 强制改密合规状态：总量、已标记待改密、仍使用初始哈希。
     *
     * @param total       用户总数
     * @param flagged     已标记下次登录必须改密
     * @param seedPasswords 仍使用 V14 初始密码哈希的账号数
     */
    public record PasswordChangeStatus(long total, long flagged, long seedPasswords) {
    }

    /** 查询合规状态（只读）。 */
    public PasswordChangeStatus passwordChangeStatus() {
        long total = userMapper.selectCount(null);
        long flagged = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getPasswordChangeRequired, 1));
        long seed = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getPassword, SEED_PASSWORD_HASH));
        return new PasswordChangeStatus(total, flagged, seed);
    }

    /** 仍使用初始密码的账号（只返回身份信息，不返回哈希）。 */
    public record SeedAccount(Long id, String username, String realName) {
    }

    /** 批量重置后交给管理员的一次性凭据。 */
    public record SeedCredential(Long id, String username, String realName, String password) {
    }

    /** 识别仍使用 V14 初始密码的账号；只读，不修改。 */
    public List<SeedAccount> seedPasswordAccounts() {
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getPassword, SEED_PASSWORD_HASH)
                .orderByAsc(User::getId));
        if (users == null) {
            return List.of();
        }
        return users.stream()
                .map(user -> new SeedAccount(user.getId(), user.getUsername(), user.getRealName()))
                .toList();
    }

    /**
     * 批量重置仍使用初始密码的账号：旧密码立即失效，返回每人一份一次性新密码。
     *
     * <p>这是管理动作，必须由管理员确认后调用；密码只在本次响应里出现，绝不写日志。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<SeedCredential> resetSeedPasswords() {
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getPassword, SEED_PASSWORD_HASH)
                .orderByAsc(User::getId));
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        List<SeedCredential> result = new ArrayList<>(users.size());
        for (User user : users) {
            String plain = randomPassword();
            user.setPassword(passwordEncoder.encode(plain));
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(user);
            result.add(new SeedCredential(user.getId(), user.getUsername(),
                    user.getRealName(), plain));
        }
        return result;
    }

    /** 14 位随机密码；字符集去掉易混字符。 */
    private static String randomPassword() {
        StringBuilder password = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            password.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    /**
     * 重置用户密码：生成一次性随机密码，旧密码立即失效。
     *
     * <p>返回值是明文新密码，只应在本次响应中交给管理员；日志绝不打印它。
     *
     * @param id 用户 ID
     * @return 新密码（明文，仅此一次）
     */
    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        StringBuilder password = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            password.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        String plain = password.toString();
        user.setPassword(passwordEncoder.encode(plain));
        user.setPasswordChangeRequired(1);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return plain;
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

        boolean superAdmin = isSuperAdmin(user);
        if (superAdmin && !isSuperAdminUsername(request.getUsername())) {
            throw new RuntimeException("超级管理员账号不能改名");
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

        user.setUsername(superAdmin ? SUPER_ADMIN_USERNAME : request.getUsername());
        user.setRealName(request.getRealName());
        user.setRole(superAdmin ? PositionRoles.DIRECTOR : request.getRole());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());

        // 如果提供了新密码，则更新密码
        if (StringUtils.hasText(request.getPassword())) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        if (superAdmin) {
            user.setStatus(1);
        } else if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }

        userMapper.updateById(user);
        syncUserRole(user.getId(), user.getRole());

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
        if (isSuperAdmin(user) && status != null && status != 1) {
            throw new RuntimeException("超级管理员账号不能停用");
        }

        user.setStatus(status);
        userMapper.updateById(user);
    }

    /**
     * 删除用户。若用户已被历史业务数据引用，回退为停用，避免外键约束导致 500。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (isSuperAdmin(user)) {
            throw new RuntimeException("超级管理员账号不能删除");
        }

        try {
            userMapper.deleteById(id);
            LambdaQueryWrapper<SysUserRole> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysUserRole::getUserId, id);
            sysUserRoleMapper.delete(wrapper);
            return true;
        } catch (DataIntegrityViolationException e) {
            user.setStatus(0);
            userMapper.updateById(user);
            return false;
        }
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

    private boolean isSuperAdmin(User user) {
        return user != null && isSuperAdminUsername(user.getUsername());
    }

    private boolean isSuperAdminUsername(String username) {
        return SUPER_ADMIN_USERNAME.equalsIgnoreCase(username != null ? username.trim() : "");
    }

    private void syncUserRole(Long userId, String roleCode) {
        LambdaQueryWrapper<SysRole> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(SysRole::getCode, roleCode)
                .eq(SysRole::getStatus, 1);
        SysRole role = sysRoleMapper.selectOne(roleWrapper);
        if (role == null) {
            throw new RuntimeException("角色不存在或已禁用");
        }

        LambdaQueryWrapper<SysUserRole> userRoleWrapper = new LambdaQueryWrapper<>();
        userRoleWrapper.eq(SysUserRole::getUserId, userId);
        sysUserRoleMapper.delete(userRoleWrapper);

        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(role.getId());
        sysUserRoleMapper.insert(userRole);
    }
}
